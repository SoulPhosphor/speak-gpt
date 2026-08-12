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
