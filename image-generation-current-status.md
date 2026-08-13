<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Image Generation — Current Status

> **Status: rebuilt architecture implemented; static audit completed August 12, 2026; runtime generation not yet re-verified by the owner.**
>
> `image-generation-rebuild-plan.md` is the historical rebuild/design record. This file records the current post-rebuild state and should be used when deciding whether old provider-specific code is still active architecture or only cleanup/migration debt.

## 1. What the static audit verified

The current image-generation request path is separated from the old provider/model-name routing:

- `ImageProviderAdapter` is a provider-neutral request/response contract.
- `ImageGeneratorCoordinator` reads the independently saved image-generator endpoint and image-generator model rather than borrowing the current chat endpoint/model.
- `ImageProviderAdapters.forEndpoint(...)` chooses the adapter from the saved endpoint/host, not from the image model name.
- OpenRouter has a dedicated `OpenRouterImageAdapter` that requests image output through the OpenRouter chat protocol.
- Other configured endpoints currently use `OpenAiImageAdapter`, which is an **OpenAI-compatible Image API protocol adapter** using `/images/generations`. It forwards the selected model ID rather than restricting generation to a named model-family allow-list.
- The image model picker does not prune unfamiliar image model IDs merely because their names do not match a hard-coded OpenAI family.
- Both `/imagine` and model-requested image generation enter the rebuilt global image-generator path.

### Compatibility boundary

Provider-neutral selection does not mean every possible provider protocol works automatically. At present:

- OpenRouter has its own protocol adapter.
- Endpoints compatible with the OpenAI-style `/images/generations` protocol can use the OpenAI-compatible adapter.
- A provider with a materially different image-generation request/response protocol still needs its own `ImageProviderAdapter` implementation and registration.

That is an adapter-capability boundary, not model-name hardwiring.

## 2. Provider-neutral presentation cleanup

The old provider-specific presentation names were removed together in the generated-image capability polish:

- the view ID is `generated_image` in all three message layouts;
- the adapter uses provider-neutral generated-image field and helper names;
- the dead provider-specific branch and unused presentation strings are gone.

### Coordinated rename rule

`generated_image` is load-bearing because the adapter expects it in each message layout. Future changes must remain coordinated across all three layouts and the adapter.

The same capability slice also established these user-facing contracts:

- generated images expose a read-only Prompt dialog and a downward-arrow save action;
- generated-image messages do not expose the ordinary text Edit action;
- the Creating Image label animates and the returned row keeps a Loading Image state until decode/render completes;
- image jobs use their own data-sync foreground keep-alive for app switching and screen-off;
- model continuation is released only after the image terminal message is published;
- failures keep the app explanation separate from the provider's sanitized error detail, matching ordinary failed chat replies.

## 3. Legacy values that should NOT be deleted yet

Some old image preferences still contain legacy image-model and resolution readers. `ImageGenerationMigration` still uses those values as one-time migration inputs.

Those are intentional migration compatibility, not current provider routing. Keep them until the migration's documented stable-release deletion gate has been satisfied.

## 4. Runtime verification still required

This audit is static. It does **not** prove that a real provider request currently succeeds end-to-end.

Before declaring the rebuild fully verified, run a smoke test that covers:

1. Configure a known-working image endpoint and model in Images settings.
2. Generate one image through `/imagine`.
3. Verify the result renders in the chat and remains visible after leaving/reopening the conversation.
4. If model-requested image generation is supported by the selected chat model, trigger one tool-requested generation and verify it uses the same global image-generator configuration.
5. Switch the chat model without changing the saved image generator and confirm the image generator does not silently follow the chat model.
6. If practical, switch the configured image endpoint/model and verify the new generator is used without changing conversation routing.

Any failure from that test should be treated as a runtime bug to diagnose, not as evidence that old provider-specific naming in the presentation layer is intended architecture.

## 5. Relationship to Phase 4 chat redesign

`chat-redesign-plan.md` is authoritative for how visual media appears in messages:

- image/generated visual media stays in the main message-content region;
- it is provider-neutral content;
- text + image renders before Message Actions;
- image-only messages are valid;
- document/file attachments follow their separate attachment ordering rule.

The provider-neutral generated-image ID/name cleanup is complete and should be preserved by the shared-message-shell work.
