<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Image Generation — Current Status

> **Status: rebuilt architecture implemented; static audit completed August 12, 2026; runtime generation not yet re-verified by the owner.**
>
> `image-generation-rebuild-plan.md` is the historical rebuild/design record. This file records the current post-rebuild state and should be used when deciding whether old DALL-E/OpenAI-specific code is still active architecture or only cleanup/migration debt.

## 1. What the static audit verified

The current image-generation request path is substantially separated from the old DALL-E/model-name routing:

- `ImageProviderAdapter` is a provider-neutral request/response contract.
- `ImageGeneratorCoordinator` reads the independently saved image-generator endpoint and image-generator model rather than borrowing the current chat endpoint/model.
- `ImageProviderAdapters.forEndpoint(...)` chooses the adapter from the saved endpoint/host, not from the image model name.
- OpenRouter has a dedicated `OpenRouterImageAdapter` that requests image output through the OpenRouter chat protocol.
- Other configured endpoints currently use `OpenAiImageAdapter`, which is an **OpenAI-compatible Image API protocol adapter** using `/images/generations`. It forwards the selected model ID rather than restricting generation to a DALL-E/GPT-image name allow-list.
- The image model picker does not prune unfamiliar image model IDs merely because their names do not match a hard-coded OpenAI family.
- Both `/imagine` and model-requested image generation enter the rebuilt global image-generator path.

### Compatibility boundary

Provider-neutral selection does not mean every possible provider protocol works automatically. At present:

- OpenRouter has its own protocol adapter.
- Endpoints compatible with the OpenAI-style `/images/generations` protocol can use the OpenAI-compatible adapter.
- A provider with a materially different image-generation request/response protocol still needs its own `ImageProviderAdapter` implementation and registration.

That is an adapter-capability boundary, not model-name hardwiring.

## 2. Cleanup that is still genuinely incomplete

The owner's concern was valid: old DALL-E naming was **not completely removed** from the active presentation layer.

Known active cleanup debt includes:

- generated-image view ID `dalle_image` in all three message layouts;
- `dalleImageStringList` in `ChatAdapter`;
- `processDalleFile(...)` in `ChatAdapter`;
- DALL-E-specific comments around generated-image rendering;
- an apparently dead `"dalle"` branch in the old OpenAI-missing helper in `ChatActivity`;
- legacy DALL-E compatibility strings that should be reference-checked and removed if unused.

These names do not appear to choose the generator/provider anymore, but they should not be propagated into the Phase 4 shared message shell.

### Coordinated rename rule

`dalle_image` is currently a load-bearing view ID because the adapter expects it in each message layout. Do not delete or rename one XML occurrence in isolation.

During the shared-message-shell work:

1. rename the generated-image view to a provider-neutral ID across all three message layouts;
2. rename the corresponding adapter field/list/helper names and comments in the same slice;
3. update every source reference atomically;
4. update the master UI-plan message-ID contract in that same change;
5. compile/test before treating the old DALL-E presentation name as removed.

## 3. Legacy values that should NOT be deleted yet

Some old image preferences still contain DALL-E-era defaults/names, including the legacy image-model and resolution readers. `ImageGenerationMigration` still uses those values as one-time migration inputs.

Those are intentional migration compatibility, not current provider routing. Keep them until the migration's documented stable-release deletion gate has been satisfied. Removing them merely to eliminate the word “DALL-E” could break upgrade behavior.

## 4. Runtime verification still required

This audit is static. It does **not** prove that a real provider request currently succeeds end-to-end.

Before declaring the rebuild fully verified, run a smoke test that covers:

1. Configure a known-working image endpoint and model in Images settings.
2. Generate one image through `/imagine`.
3. Verify the result renders in the chat and remains visible after leaving/reopening the conversation.
4. If model-requested image generation is supported by the selected chat model, trigger one tool-requested generation and verify it uses the same global image-generator configuration.
5. Switch the chat model without changing the saved image generator and confirm the image generator does not silently follow the chat model.
6. If practical, switch the configured image endpoint/model and verify the new generator is used without changing conversation routing.

Any failure from that test should be treated as a runtime bug to diagnose, not as evidence that the old DALL-E naming in the presentation layer is the intended architecture.

## 5. Relationship to Phase 4 chat redesign

`chat-redesign-plan.md` is authoritative for how visual media appears in messages:

- image/generated visual media stays in the main message-content region;
- it is provider-neutral content;
- text + image renders before Message Actions;
- image-only messages are valid;
- document/file attachments follow their separate attachment ordering rule.

The coordinated provider-neutral generated-image ID/name cleanup is scheduled with the shared-message-shell work so the new chat architecture does not fossilize the legacy DALL-E presentation terminology.
