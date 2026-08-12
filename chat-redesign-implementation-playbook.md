<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Implementation Playbook

**Status:** Ready for implementation after the planning package is present on the implementation branch.

This file converts the owner-approved Phase 4 chat design into an implementation sequence optimized for a long-running AI coding session. It does **not** replace `chat-redesign-plan.md` or its dated addenda. Those files define the product/visual contract. This playbook defines the safest order in which to build it.

The implementation agent should make ordinary implementation-level decisions by inspecting current code. Do not reopen owner-approved design decisions merely because another implementation is possible. Stop for owner input only when code reality exposes a genuine product decision, destructive migration risk, or conflict between authoritative documents.

## 0. Mandatory reading and startup audit

Before editing code, read in this order:

1. `CLAUDE.md` — repository-wide safety, branch, CI, and coding rules.
2. `chat-redesign-plan.md` — main Phase 4 visual/behavior contract.
3. `chat-redesign-plan-addendum-2026-08-11.md` — final geometry and settings reconciliation.
4. `chat-redesign-plan-addendum-2026-08-12.md` — Thinking/reasoning, provider verification, theme readiness, and handoff rules.
5. `ui-style-guide.md` — shared visual-system and theme-attribute rules.
6. `ui-style-adoption.md` — current screen/style conversion status.
7. `ui-redesign-plan.md` — broader UI architecture and load-bearing chat/theme contracts.
8. `image-generation-current-status.md` — current image-generation architecture and the remaining provider-neutral presentation cleanup.

Then perform a **read-only startup audit** of the current branch before implementation:

- identify the current `ChatActivity`, `ChatAdapter`, message model/storage classes, three current message layouts, chat composer layout, Settings/Control Center code, Preferences/GlobalPreferences APIs, theme/style resources, image-generation presentation code, and any provider/response adapters used by chat;
- verify every load-bearing view ID and listener named in the plans still exists;
- identify any code changes made after the plan was written that affect these paths;
- record any plan/code mismatch before editing.

Do not treat stale filenames, line numbers, or internal identifiers in the plan as authority over current code. Preserve the **behavioral contract** while adapting to verified current structure.

## 1. Working method and checkpoint rule

Implement the phases below **in order**. A single long-running AI conversation may complete all phases, but each phase is a separate checkpoint.

For every phase:

1. inspect the relevant current code before editing;
2. implement only that phase plus the smallest prerequisite needed to make it compile;
3. statically verify IDs/resources/references and review the diff for accidental unrelated changes;
4. run the repository's required build/CI gate when available;
5. if green, commit the phase with a focused commit message;
6. only then continue to the next phase.

Do not accumulate several risky phases into one giant commit. If CI fails, fix the current phase before moving forward.

When a phase exposes a genuine design conflict, stop there. Do not silently invent a new product rule to keep momentum.

---

# Phase 1 — Appearance foundation and legacy settings migration

## Goal

Create the new Appearance destination and establish the preference/state layer the message redesign will consume, without changing message-row rendering yet.

## Implement

- Add the **Appearance** Control Center row in the approved location and wording.
- Add the approved Appearance controls:
  - Profile Images
  - Names
  - AI Bubble
  - User Bubble
  - Model Names
  - Token Usage
  - Hardware Keyboard Shortcuts
  - User name font/size
  - AI name font/size
  - editable font preview text
- Preserve the existing Desktop Mode stored value/behavior under **Hardware Keyboard Shortcuts**.
- Convert old **Hide Model Names** behavior into the positive **Model Names** control without resetting users. Preserve effective behavior when translating the old negative preference.
- Remove the obsolete Classic/Non-Classic UI/product concept.
- Remove Monochrome and its obsolete behavior as specified.
- Remove the standalone AMOLED settings tile while preserving existing AMOLED wiring/capability for future Themes.
- Leave Auto-save Chats completely untouched.
- Add only the shared/theme-aware resources needed by this screen and future chat controls. Do not design palettes.

## Theme/style gate

- Follow `ui-style-guide.md`.
- Use shared row/toggle/dropdown/field patterns where they apply.
- No palette-specific hard-coded colors.
- If a new semantic chat role is needed, centralize it behind a theme-aware resource/attribute or shared drawable/code path.

## Do not do yet

- no message-row redesign;
- no provider/reasoning work;
- no composer behavior changes;
- no image-generation cleanup beyond what is strictly required to keep the settings screen compiling.

## Phase verification

- Existing stored Desktop Mode behavior survives the move.
- Existing Hide Model Names behavior survives the positive-label migration.
- Auto-save Chats is untouched.
- AMOLED capability still exists even though its standalone tile is gone.
- Appearance opens and all controls persist values correctly.

**Checkpoint commit:** `phase4: add Appearance foundation and migrate chat display settings`

---

# Phase 2 — Shared adaptable message shell and final geometry

## Goal

Replace the old Classic/Non-Classic message-layout split with one adaptable message architecture while preserving all existing message behaviors and load-bearing actions.

## Implement

- Build one shared/adaptable message shell for AI and user messages using the verified current Views/XML architecture.
- Preserve Markwon/markdown rendering, streaming updates, selection/edit/retry/speak/share/report/copy behavior, generated content hooks, attachment hooks, and existing click/visibility logic.
- Preserve load-bearing IDs unless a coordinated rename is explicitly required by the plan.
- Apply final geometry:
  - `27dp` mirrored speaker-side bubble distance;
  - user-only `26dp` left inset, affecting only the user's left boundary;
  - fixed user right anchor;
  - `16dp` uniform bubble radius;
  - `14dp` horizontal/vertical internal message padding;
  - `53dp` visible message-unit separation;
  - portrait size `76dp`, X `-15dp`, Y `-36dp`, mirrored;
  - portrait-on name X `52dp`, Y `-30dp`, mirrored.
- Implement Profile Images / Names / AI Bubble / User Bubble visibility using the same architecture rather than separate renderers.
- Implement portrait-off name placement relationally:
  - AI name begins `1dp` from AI bubble left edge;
  - user name begins `1dp` from user bubble right edge, mirrored;
  - name vertically centered on the bubble top border;
  - border stops around glyphs and never draws through them;
  - no filled/color badge behind the name.
- Implement the shared **Chat Name Style** abstraction and the approved bundled font selection/size behavior.

## Important invariants

- Bubble visibility never changes speaker alignment.
- User left inset never expands or resizes the screen/container.
- Internal message padding is not retuned while solving outer geometry.
- Portrait/name decorative space collapses cleanly when disabled.
- Message body typography is not changed by chat-name typography.

## Do not do yet

- no Message Details popup implementation beyond reserving the future action slot if useful;
- no durable model/token metadata storage yet;
- no Thinking provider parsing;
- no composer redesign.

## Phase verification

Test the meaningful visual combinations, at minimum:

- portraits on/off;
- names on/off;
- AI bubble on/off;
- user bubble on/off;
- portrait + name and portrait-off + name;
- long and short AI/user messages;
- no layout-width growth when user inset changes;
- existing message actions still work and stream updates still render.

Use `chat-geometry-tuner.html` and `chat-portrait-geometry-mockup.html` only as design aids. The plans are authoritative if they disagree.

**Checkpoint commit:** `phase4: add adaptable message shell and final geometry`

---

# Phase 3 — Message Actions, Message Details, and durable per-message metadata

## Goal

Add the universal metadata affordance and optional persistent model/token attribution without confusing message identity with current-chat settings.

## Implement

- Add universal Message Details `ⓘ` as the **far-left Message Action** for both user and AI messages.
- It is permanent. There is no toggle that removes it.
- Match the visual/touch sizing of the existing Message Actions rather than emphasizing it.
- Implement the small anchored, selectable/copyable Message Details popup with normal outside-tap/back dismissal and internal scrolling if needed.
- Show only fields meaningful for that message type.
- Candidate fields include date/time, model, provider, endpoint/source when meaningful, token data, response identity, and generation status.
- Add durable per-message storage for the **actual response model identifier** and provider-reported token usage where available.
- Implement **Model Names** and **Token Usage** persistent display toggles.
- Persistent metadata sits beneath the speaker name/identity area.
- If both model and token values are shown, use one subordinate line when space permits, separated by a centered dot, with natural wrapping rather than forced truncation.
- If Names is off, metadata moves into the top identity region without leaving a blank username row.
- Token Usage defaults Off.
- Never relabel historical messages from the model currently selected for the chat.
- Never invent/estimate a persistent token value when the provider did not report usable data.

## Migration/compatibility rule

If the current message persistence schema cannot store the required metadata, make the smallest backward-compatible schema/storage change necessary. Existing chats must still load. Missing historical metadata is acceptable; fabricated metadata is not.

## Phase verification

- Switch models within one conversation and verify old turns retain their original model attribution.
- Toggle Model Names and Token Usage independently.
- Reopen the conversation and confirm persisted metadata remains attached to the correct messages.
- Confirm `ⓘ` remains present regardless of the persistent-display toggles.

**Checkpoint commit:** `phase4: add message details and per-turn model token metadata`

---

# Phase 4 — File attachments and provider-neutral visual media cleanup

## Goal

Integrate existing file/image behavior into the shared shell without reinventing working content semantics, while removing the remaining DALL-E-specific **presentation** naming in one coordinated safe slice.

## Implement

### File/document attachments

Preserve existing attachment behavior and terminology. Apply the approved ordering:

- text message: text → Message Actions → file/document attachment row/tray;
- attachment-only message: identity → attachment(s) inside main bubble/reading surface → Message Actions;
- no fake visible user text for attachment-only messages.

### Images / generated visual media

Preserve existing working image behavior. Apply the approved provider-neutral ordering:

- with text: identity/metadata → text → image/generated visual media → Message Actions;
- image-only: identity/metadata → image/generated visual media → Message Actions.

Do not route ordinary image content into the document/file tray.

### Coordinated legacy-name cleanup

Static audit already identified presentation-layer cleanup debt including `dalle_image`, `dalleImageStringList`, `processDalleFile`, DALL-E-specific comments, and an apparently dead old `"dalle"` branch.

Before renaming/removing:

1. re-verify all references in current code;
2. distinguish active presentation names from intentionally retained migration compatibility;
3. rename the generated-image view ID and all adapter/layout references atomically to a provider-neutral name;
4. rename corresponding adapter lists/helpers/comments in the same slice;
5. remove dead DALL-E/OpenAI presentation branches/strings only when reference checks prove them unused;
6. **do not delete legacy image preference readers still required by `ImageGenerationMigration` until its documented deletion gate is satisfied.**

## Runtime note

The owner has not yet performed the separate end-to-end image-generation smoke test. Do not block this phase on that manual test if the code compiles and existing static behavior can be preserved. Record runtime image generation as a later verification item rather than claiming it has been proven.

## Phase verification

- Existing file attachment interactions still work.
- Existing generated/attached image rendering hooks still compile and behave as before.
- No active message-presentation identifier remains DALL-E-specific after the coordinated rename.
- Migration compatibility is preserved.

**Checkpoint commit:** `phase4: integrate attachments and neutralize generated image presentation`

---

# Phase 5 — Provider-neutral Thinking/reasoning support

## Goal

Display provider-supplied reasoning/thinking content in the approved movable accordion without model-name hard-coding.

## Provider verification responsibility

The implementation agent, not the owner, must research and verify this at implementation time.

- Inventory every chat provider/protocol adapter actually supported by the current app.
- Inspect current primary API/provider documentation and the app's parsing/streaming paths.
- Determine which supported protocols expose separate reasoning, thinking content blocks, or reasoning summaries.
- Normalize the forms the app can actually receive into one provider-neutral per-message reasoning representation.
- Document any supported provider/protocol for which reasoning exists upstream but cannot be obtained through the app's current API path.
- Do not use a hard-coded list of model names to decide whether a Thinking row exists.

## Implement

- Persist provider-supplied reasoning with the specific AI message that produced it.
- Support separately streamed reasoning when the provider supplies it, without mixing it into final-answer text.
- Insert the Thinking block as its own movable child after identity/compact metadata and before the final response body.
- Default collapsed.
- Visible label: **Thinking** followed by a thin outline-style disclosure chevron.
- Collapsed chevron points right; expanded chevron points down.
- Tapping the row only expands/collapses existing content. It must not regenerate the answer.
- Show the row only when normalized provider-supplied reasoning/summary content actually exists.
- If the provider supplies only a summary, display it as the supplied summary rather than pretending it is raw reasoning.
- Never expose hidden/internal reasoning the provider did not return to the app.

## Phase verification

- Focused parsing tests for each supported reasoning response shape where the test architecture permits.
- Persistence/reopen test.
- Streaming test where a supported provider streams reasoning separately.
- Normal non-reasoning responses show no empty Thinking row.

**Checkpoint commit:** `phase4: add provider-neutral Thinking disclosure`

---

# Phase 6 — Multiline composer and keyboard/inset hardening

## Goal

Keep the current clean short-message composer behavior while making long drafting substantially easier and preventing the composer from slipping under the software keyboard.

## Implement

- Preserve current attach/mic/send/conversation behaviors and their IDs/listeners/state logic.
- At its compact height, the editable field begins in the horizontal space **between the existing side controls**, matching the current mental model.
- As text wraps, the text area grows **upward**, not underneath/through the controls.
- Keep the controls bottom-anchored and stable while the text region expands above them.
- Set a sensible maximum expanded height using the current design reference (about `120dp` unless verified current code/style already defines the intended maximum).
- Beyond that height, the draft scrolls internally rather than consuming the entire chat viewport.
- Preserve hardware keyboard semantics now exposed as Hardware Keyboard Shortcuts.
- Audit the existing keyboard-frame/window-inset handling and fix the reported case where the composer can sit partly beneath the software keyboard.
- Use one coherent inset mechanism. Do not stack a second competing keyboard-offset hack on top of the existing system.
- Keep composer styling theme-aware and future-palette compatible.

## Phase verification

- short one-line message looks/behaves like the compact composer;
- multiline draft grows upward cleanly;
- very long draft scrolls internally;
- attach/mic/send behavior remains intact;
- voice/microphone state tinting remains intact;
- hardware keyboard Enter/Shift+Enter/Escape/Back behavior remains intact;
- software keyboard cannot cover the composer on the Pixel test device and common orientation/configuration changes.

**Checkpoint commit:** `phase4: improve multiline composer and keyboard insets`

---

# Phase 7 — Integration hardening, header/insets, and regression sweep

## Goal

Validate the complete Phase 4 system as one coherent chat experience and fix integration defects without reopening the approved design.

## Implement / verify

- Fix the standing intermittent chat-header/title disappearance bug if still reproducible/present.
- Verify the future drawer can still integrate without requiring message-row architecture changes.
- Audit chat top/bottom/system-bar insets, rotation, configuration changes, and large font scale.
- Test long conversations and RecyclerView reuse/recycling to catch stale visibility/state on portraits, names, metadata, Thinking, images, attachments, and Message Details.
- Verify streamed responses do not inherit stale metadata/reasoning from recycled views.
- Verify all Appearance combinations continue to preserve readable surfaces and theme roles.
- Verify decorative-background readiness rules remain architecturally possible; do **not** implement future decorative backgrounds or palette design in this phase.
- Verify no hard-coded palette-specific chat colors were introduced.
- Verify Classic/Non-Classic and Monochrome are truly removed as product UI concepts, standalone AMOLED tile is gone but wiring remains, and Auto-save Chats was not touched.
- Re-run static checks for load-bearing IDs/listeners and generated-image rename consistency.
- Run the full required CI/build gate and resolve all failures.

## Manual/runtime items that may remain explicitly unverified

If the owner has not chosen to test these yet, record them as follow-up verification rather than blocking completion or claiming success:

- real end-to-end image generation against a configured provider/model;
- provider-specific Thinking behavior that requires credentials/endpoints unavailable to the implementation environment.

## Completion output

At the end of Phase 7, provide the owner a concise implementation report containing:

- phase commits;
- CI/build status;
- any deliberate compatibility/migration choices;
- anything that could not be runtime-tested and why;
- any follow-up that genuinely requires owner action.

**Checkpoint commit:** `phase4: harden chat redesign integration`

---

# Stop conditions for the implementation agent

**Do not ask the owner about ordinary coding choices.** Inspect current code and choose the smallest implementation that satisfies the contract.

Stop and ask only when one of these is true:

1. the current code makes two owner-approved requirements mutually incompatible;
2. implementation would require a destructive data migration not already authorized;
3. a load-bearing behavior cannot be preserved without changing the product design;
4. a new user-facing choice is genuinely required and the plans do not define it;
5. current code reveals that an allegedly dead feature is still actively used in a way that changes the approved removal decision;
6. a theme/style requirement cannot be met without creating a materially new visual system rather than a shared semantic role.

Do **not** stop merely because:

- an exact Kotlin/XML technique is not specified;
- a filename/line number moved;
- a provider uses a different field name than an example in the plan;
- a shared resource needs to be added to express an already-approved semantic role;
- a responsive constraint needs a small implementation correction to preserve the approved geometry.

# Definition of done

Phase 4 is implementation-complete when:

- all seven phases are committed and pass the repository's required build/CI checks;
- the owner-approved chat geometry and identity treatment are implemented;
- Appearance controls persist and migrate correctly;
- the permanent `ⓘ` Message Details affordance works;
- model/token metadata belongs to the correct historical messages;
- attachments and generated visual media retain their working behavior and approved ordering;
- active generated-image presentation naming is provider-neutral while migration compatibility remains safe;
- provider-supplied reasoning is normalized and displayed through the approved Thinking accordion where available;
- the composer expands upward and remains above the software keyboard;
- the implementation remains compatible with shared styles, future palettes/custom themes, and preserved AMOLED wiring;
- no owner-approved design decision was silently replaced during implementation;
- any unperformed runtime provider tests are explicitly documented rather than represented as verified.
