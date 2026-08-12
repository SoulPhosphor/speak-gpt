<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Plan Addendum — August 12, 2026

This addendum is part of the Phase 4 chat redesign plan and records the owner-approved treatment for provider-supplied reasoning / thinking content. Where this addendum conflicts with older chat-plan wording about reasoning presentation, this addendum wins.

## 1. Thinking / reasoning presentation

Reasoning content supplied separately by a provider is part of the AI message unit but remains visually distinct from the final response.

### 1.1 Placement

When reasoning content exists for an AI message, place a compact expandable **Thinking** row:

1. after the enabled speaker identity / compact model-token metadata region; and
2. before the final response body.

This preserves the reading order: identity → optional reasoning disclosure → final answer.

The Thinking block must be its own component inside the shared message shell so its position can be changed later without rewriting message text or provider logic.

### 1.2 Collapsed / expanded control

- Default state: **collapsed**.
- Visible label: **Thinking**.
- Put the disclosure chevron **after** the word Thinking.
- Use a thin outline-style chevron, not a filled triangle.
- Collapsed state: chevron points toward the side / right (`>` relationship).
- Expanded state: chevron points downward (`v` relationship).
- Tapping the Thinking row toggles the reasoning body open and closed.
- Expanding/collapsing must not alter or regenerate the final answer.

Conceptual presentation:

`Thinking  >`

and, when open:

`Thinking  v`

followed by the provider-supplied reasoning content.

Use the app's normal vector/icon treatment rather than literal text glyphs if an appropriate chevron drawable already exists.

### 1.3 Provider-neutral detection

Do **not** decide whether to show Thinking by hard-coded model-name lists or by guessing that a model is a "thinking model."

Instead:

- provider/protocol adapters inspect the actual response payload;
- known provider-specific fields or content blocks that contain separately exposed reasoning are normalized into one provider-neutral per-message reasoning representation;
- show the Thinking row only when that normalized reasoning content is actually present;
- if the provider exposes only a reasoning summary, preserve/display the summary as supplied rather than pretending it is raw reasoning;
- if the provider exposes no reasoning content, render no empty Thinking row.

Provider-specific field names such as `reasoning`, `reasoning_content`, reasoning summaries, or thinking content blocks belong in adapter/parsing logic, not in the message UI contract.

### 1.4 Persistence and message ownership

Reasoning belongs to the specific AI response that produced it.

- Preserve provider-supplied reasoning with that message so it remains available after leaving and reopening the conversation.
- Switching the chat model later must not relabel, replace, or discard reasoning belonging to earlier turns.
- Do not reconstruct historical reasoning from the currently selected model or from transient logs.
- Streaming implementations may update the reasoning block while the response is in progress when the provider streams reasoning separately, but the persisted completed message is the source of truth after completion.

### 1.5 Relationship to Message Details and tokens

- The universal Message Details `ⓘ` remains permanently available and is not replaced by the Thinking accordion.
- **Model names** and **Token usage** remain independent persistent-display toggles as specified in the main plan.
- The Thinking row is content-presence driven, not an Appearance toggle: if a response actually contains provider-supplied reasoning, the collapsed disclosure is available automatically.
- Do not make raw hidden/internal chain-of-thought visible when the provider does not supply it to the app. Display only reasoning or summaries actually returned through the supported API response.

## 2. Initial implementation rule

Implement the Thinking block as a movable child of the adaptable AI message shell rather than baking it into the final-answer TextView/markdown string. This deliberately keeps future repositioning cheap if device testing shows that the owner prefers the disclosure below the answer or elsewhere in the message unit.

Do not let this reasoning feature delay or destabilize the already-approved message geometry, composer behavior, attachments, generated-image cleanup, Message Actions, or Message Details work. It should consume normalized message data and participate in the same per-message rendering architecture.

## 3. Provider reasoning verification is an implementation responsibility

The owner is **not** responsible for researching which providers expose reasoning or what field names they use.

When the Thinking feature is implemented, the implementing agent must:

1. inventory the provider/protocol adapters that are actually supported by the app at that time;
2. inspect the current response schemas and parsing paths used by those adapters;
3. determine which supported protocols expose separate reasoning, thinking blocks, or reasoning summaries in responses and/or streams;
4. normalize each supported exposed form into the shared per-message reasoning representation described above;
5. add focused parsing/persistence tests where the existing test architecture permits;
6. gracefully render no Thinking row when a provider supplies no separate reasoning;
7. document any supported provider/protocol for which reasoning exists upstream but cannot currently be obtained through the app's chosen API path.

Do not solve this with a static list of model names. Provider capability and returned payload are the source of truth.

If provider documentation or SDK/API behavior has changed by implementation time, the implementing agent must verify the current primary provider/API documentation rather than asking the owner to research it manually.

This verification belongs to the implementation task itself and is not a prerequisite the owner must complete before handing off Phase 4.

## 4. Theme/style readiness requirement

The chat redesign may be implemented **before** the owner resumes palette/theme design. The implementation must nevertheless remain fully compatible with the app's shared style and future theme architecture.

The binding rules are:

- Follow `ui-style-guide.md` and the shared `Widget.App.*` / `AppButton.*` style system where an approved shared family applies.
- Resolve repeated chat colors through theme attributes, mapped Material roles, shared drawables, or another single shared theme-aware path. **Do not hard-code palette-specific colors into the new message shell, metadata, Thinking block, composer, portrait/name treatment, Message Actions, or Appearance screen.**
- The canonical future palette contract is the semantic-zone system described by `ui-style-guide.md` and `ui-redesign-plan.md`. The chat implementation must not close off preset palettes or future user-created themes.
- If the existing theme attributes do not yet contain every semantic chat zone needed by the redesigned chat, centralize the missing role behind a shared resource/theme attribute rather than copying raw color values into individual message layouts or Kotlin branches.
- It is acceptable to map a new shared chat role to the current/default visual value for now. Choosing or polishing future palette colors is **out of scope** for this implementation.
- Do not resume, redesign, or polish the paused AMOLED/theme project merely because chat is being rebuilt. Preserve existing AMOLED capability/wiring as already specified and keep the new chat architecture capable of consuming the future theme system when that work resumes.
- Decorative future chat backgrounds must continue to obey the main plan's readability rule: readable message text receives an opaque theme-controlled reading surface when a non-solid/decorative background requires one.

The goal of this requirement is architectural compatibility, not color design. The owner should be able to design palettes later without requiring the Phase 4 message architecture to be rewritten.

## 5. Handoff readiness

With the owner-approved chat geometry, identity treatment, metadata controls, Message Details behavior, attachment/image ordering, composer behavior, settings migration rules, and Thinking treatment now documented, Phase 4 is ready to be implemented from the plan.

An implementing agent should read, in order:

1. `CLAUDE.md` for repository-wide safety rules;
2. `chat-redesign-plan.md` for the main Phase 4 contract;
3. `chat-redesign-plan-addendum-2026-08-11.md` for locked geometry/settings reconciliation;
4. this August 12 addendum for Thinking/reasoning, provider verification, and theme-readiness requirements;
5. `ui-style-guide.md` and `ui-style-adoption.md` for shared visual-system rules and conversion status;
6. `ui-redesign-plan.md` for broader load-bearing chat/theme contracts;
7. `image-generation-current-status.md` when touching generated-image presentation cleanup.

Implementation details may be resolved by inspecting current code where the plan deliberately leaves them implementation-level. Do not reopen owner-approved visual/product decisions merely because a different implementation is possible. Stop for owner input only when code reality exposes a genuine conflict, destructive migration risk, or new product decision not covered by these documents.
