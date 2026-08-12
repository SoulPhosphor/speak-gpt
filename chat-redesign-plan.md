<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan

> **August 10, 2026 owner ruling — Phase 4 chat visual contract:** The owner has approved the chat-message visual system below and wants the chat restyle to proceed without waiting for the drawer. The current chat header/title treatment may remain for the first pass; drawer integration must be able to land later without requiring a second rewrite of message rows. "Classic" and "non-classic" are now migration/reference terms only and are not future user-facing chat modes. The new Appearance destination is approved as part of Phase 4; palette controls can be added to that same destination later when palette work resumes.
>
> **August 12, 2026 owner ruling — per-message metadata, visual media, and composer behavior:** Persistent metadata is split into independent **Model names** and **Token usage** toggles rather than an "Always show Message Details" mode. Enabled compact metadata belongs directly beneath the speaker name/identity line. The universal `ⓘ` remains the full on-demand metadata view. Generated/attached images are provider-neutral message content and must not be modeled as DALL-E-specific UI. The composer begins in the horizontal space between its action buttons and grows upward as text wraps, preserving the current clean button anchoring and behavior.

## 1. Chat name typography

Section 4.6 remains binding for app-wide/body typography, but chat speaker names are now an explicitly configurable identity role. Do not implement this by scattering `fontFamily`, raw sizes, or per-layout typography attributes through the three message layouts.

Create one shared **Chat Name Style** abstraction that owns at least:

- font family;
- text size in `sp`;
- weight/style where supported by the selected family;
- the bordered name-label treatment used by the message component.

Appearance owns two defaults:

1. **User name style** — default font + default name size for the user's own displayed name.
2. **AI name style** — default font + default name size for AI/companion names.

A companion may optionally override the default AI name font and/or size in the companion editor. If no override is set, it inherits the Appearance AI-name defaults. No per-message typography is allowed. Message body typography is not changed by this feature.

The current `18sp` chat-name size is the baseline/default reference because the owner considers the current name size good. Alternate fonts may have different apparent sizes, so the Appearance screen must allow the name size to be adjusted independently for User and AI defaults. Exact picker/size-control UI is not locked until the initial font set is chosen.

Approved fonts should be exposed through the shared chat-name style rather than direct layout overrides. Font files/provider strategy is an implementation detail, but the chat UI must work without changing message-body font behavior.

## 2. Bubble geometry override

- Message bubbles / reading surfaces: **Section 4 of this file is authoritative.** Use the current non-classic bubble geometry as the reference: approximately **16dp uniform corner radius**. Do not use the older 20dp / 6dp tail-corner proposal unless the owner later changes this ruling.

## 3. Typography scope rule

- **Scoped exception for chat identity names:** Section 4 approves user-selectable chat-name fonts and sizes. Those choices must flow through a shared Chat Name Style and may use approved font resources. Do not apply those fonts to message-body text or general app typography.

---

## 4. Phase 4 chat visual contract

This subsection is the visual authority for the Phase 4 message redesign. Chat is the highest-risk UI in the app; implement this contract as one adaptable message system while preserving every behavior and ID contract in Sections 9.1 and 9.2, except for a deliberately coordinated provider-neutral generated-image ID rename described in Section 4.7.2.

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

### 4.2 Alignment, width, and edge gutters

- **AI messages align left.** They may use nearly the full available chat width, while retaining normal screen-edge breathing room.
- **User messages align right.** A visible empty gutter must always remain on the left so speaker direction remains obvious even when the user's bubble is disabled. The current non-classic user's roughly `36dp` start-side gap is the working minimum reference; do not let user text expand edge-to-edge across the screen.
- Bubble visibility must never change speaker alignment.
- When an AI bubble is disabled, AI text may reclaim the space that would otherwise be consumed by bubble border/padding and can become somewhat wider, but it still keeps a small outer screen margin.

### 4.3 Bubble / reading-surface geometry

The owner prefers the **current non-classic bubble corner geometry**. Use approximately **16dp uniform corners** as the Phase 4 reference for both AI and user surfaces.

A bubble is a visual treatment, not a separate message architecture. Toggling a bubble may change background, border, padding, and usable width, but it must not remove content, identity, actions, or message details.

If a name and a bubble are both enabled, the bordered name label **interrupts/intersects the bubble border** so the name reads as part of the message identity treatment rather than as detached text.

A name border is independent from the message bubble. If the bubble is off but the name is on, the name may still use its own bordered label treatment.

### 4.4 Profile images / portraits

Profile images are optional and are identity decoration.

- AI portrait: upper-left of the AI message region.
- User portrait: upper-right of the user message region.
- The portrait should visually overlap the bubble/reading-surface corner rather than sitting as a tiny icon in a separate column.
- Use an inset/pseudo-wrap approach: early text receives enough inset/padding to clear the portrait; later lines/content may use the full message width. Do not build arbitrary text-flow-around-view logic.
- The working Phase 4 target is **about 80dp of visible portrait artwork**, with the overall portrait/frame area likely around **80–88dp** depending on the final frame/border treatment. This is intentionally much larger than the current chat portrait and approximately twice the visible artwork in the current Chats-list portrait. Treat this as a first-device-test target, not an immutable constant.

If profile images are disabled, all reserved portrait inset/overlap space must collapse cleanly.

### 4.5 Names and compact per-message metadata

Names are optional and independent from portraits and bubbles.

- Default baseline name size: **18sp**, matching the current chat size the owner approved visually.
- Names use the dedicated Chat Name Style defined in Section 4.6.
- With **Name + Bubble**, the name label interrupts the bubble border.
- With **Name + no Bubble**, the name remains a distinct bordered identity label; do not invent a fake message border merely to support the name.
- With **Portrait + Name**, compose them as one identity cluster at the speaker-side top corner.
- With neither enabled, the message may reduce to content + actions/details without leaving empty identity space.

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

The current non-classic chat is too visually compressed. The owner defines the gap as the visible edge-to-edge separation between neighboring message units.

- Working target: **24dp vertical separation between message units**, approximately double the current non-classic 12dp top spacing.
- Apply the same visual rhythm when bubbles are disabled by treating the entire message region as the unit boundary.
- Portrait/name overlap must not accidentally consume this gap and make neighboring messages appear crowded again.

### 4.7 Message Actions

Use the existing action icons and preserve their current relative ordering and per-message visibility behavior. Do not redesign/reorder the existing actions during the first Phase 4 implementation.

Add a universal **Message Details `ⓘ` icon as the far-left action** for both user and AI messages. It remains the first action regardless of which existing actions are visible for that message type.

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

Because the current adapter requires the generated-image view ID in every message layout, do **not** casually delete or rename it in XML by itself. During the shared-message-shell work, perform one coordinated rename to a generic generated-image/message-image identifier across all three message layouts and every adapter reference, then update the master Section 9.2 contract in the same change. Until that coordinated migration lands, the old ID remains technically load-bearing even though its name is obsolete.

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
- The standing header-vanishing bug in Section 7.4 still must be fixed as part of Phase 4.
- Future drawer work may add/repurpose the upper-left drawer control, but it must not require a rewrite of the message component.
- Do not move per-message model/provider/identity metadata into the header. Mixed-model conversations require per-message attribution; compact model/token fields belong in the message identity region and the full set belongs in Message Details.

### 4.10 Appearance destination and Control Center entry

Phase 4 introduces a dedicated **Appearance** destination for chat presentation controls. Palette/theme controls may be added to this same destination later when Phase 2 resumes; the screen itself no longer waits for palette work.

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

**Name style controls**

7. User name font — default selection
8. User name size — adjustable; current 18sp is the baseline/default reference
9. AI name font — default selection
10. AI name size — adjustable; current 18sp is the baseline/default reference

Provide a visual name preview when implementing font selection so users can judge apparent size/style rather than guessing from a font name alone. The preview must include an editable **Preview text** field. Users can type their actual name, a companion name, or any other sample text, and every font preview updates to render that exact text. The preview text is only a picker aid; it does not rename the user or companion. Preserve the typed preview text while the user remains on the Appearance screen so side-by-side comparison stays stable.

Companion editor:

- Add optional AI-name style overrides for that companion (font and size).
- Default state is **Use Appearance default**.
- An override changes only that companion's displayed chat name, not body text and not other companions.

The legacy Classic/non-classic chat-layout choice is retired as a product concept once this new skinning is complete. Old identifiers/preferences may be referenced during migration, but future UI copy and architecture must use the new independent controls rather than preserving two monolithic modes.

The fresh-install/default and existing-user migration mapping for Profile images, Names, AI bubble, User bubble, and Model names remains a pre-implementation owner decision. Token usage is already locked Off by default. Do not silently invent the remaining defaults while wiring preferences.

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

1. Introduce Appearance destination + preferences without changing chat behavior. Do not invent unresolved migration/default values.
2. Build the shared/adaptable message shell while preserving existing behavior contracts; perform the coordinated provider-neutral generated-image ID/name cleanup described in Section 4.7.2 rather than propagating DALL-E naming into the new shell.
3. Apply alignment, width, bubble geometry, and 24dp message spacing.
4. Add optional portraits and names, including overlap/inset behavior and Chat Name Style.
5. Add per-message model/token stamping needed by the compact metadata and Message Details contract, without changing model routing.
6. Move/normalize Message Actions into the message region; add `ⓘ` at far left while preserving existing action order/behavior; apply the file and image ordering rules in Sections 4.7.1 and 4.7.2.
7. Add anchored, selectable Message Details popup and compact Model names / Token usage rendering.
8. Restyle the composer/input using Section 4.14 only after message rendering is stable; do not touch voice/send state behavior.
9. Close the Section 7.4 top-bar/inset/layout bugs and verify long-chat + rotation/config-change behavior.

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

| Control Center | `SettingsActivity.kt`, `activity_settings.xml`, `TileFragment` | Shared row/card restyle; add **Appearance** directly beneath **Images** with subtitle **“Customize the look of your chat.”** Appearance owns Phase 4 chat display/name-style/model/token controls now and receives palette controls later when Phase 2 resumes. |

## 6. Relationship to palette work

Phase 2 no longer creates the Appearance destination. Phase 2 adds palette/theme controls to the **existing Appearance screen created by Phase 4**.

## 7. Phase 4 summary

- **Phase 4 — Chat restyle:** implement the binding visual contract in Section 4: adaptable left/right message shell; optional portraits, names, and independent AI/user bubbles; compact optional model/token metadata beneath identity; current non-classic 16dp bubble geometry; 24dp message spacing; existing action icons/order with universal far-left `ⓘ`; file attachments below Message Actions when text exists and in the main message content region when attachment-only; images/generated visual media kept in the main content region using provider-neutral naming; anchored/selectable Message Details; dedicated Appearance screen and Control Center row; upward-growing composer anchored between its action buttons; later-background compatibility; stable current header treatment unless safety fixes require change. Single UI now — no `AssistantFragment` to mirror. **Must also resolve the standing intermittent top-bar/header-vanishing bug — see Section 7.4.** Drawer work may land later without redesigning message rows.
