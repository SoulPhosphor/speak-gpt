<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Implementation Playbook

## Authority

`chat-redesign-plan.md` is the **only product/design authority** for this work.

This playbook defines implementation order and safety only. It may not add, remove, reinterpret, or override product/design decisions from the plan.

The app-wide style guide remains separate. Consult it only when the current implementation phase directly touches shared overall-app styles/theme roles; do not treat it as part of the chat-redesign design package.

## Start or resume

If work is already in progress on an implementation branch/workspace, **continue from the current checkpoint**.

Do not restart, re-clone, reconstruct, re-checkpoint, or broadly re-audit completed work.

At the start of a fresh implementation session:

1. Read `CLAUDE.md` for repository safety rules.
2. Read `chat-redesign-plan.md` once as the design contract.
3. Read only the current phase below.
4. Inspect only the current code/files directly required for that phase.

There is **no repository-wide startup audit**.

Use targeted searches only when a concrete current task requires them, such as finding every reference to a load-bearing view ID before a coordinated rename.

Supporting material is on-demand only:

- app-wide style guide: only for shared style/theme work in the current phase;
- image-generation status material: only while doing the generated-image presentation cleanup;
- current primary provider/API documentation: only during provider-reasoning verification;
- geometry HTML aids: only if Phase 2 needs visual reference for the already-approved numbers.

Do not read unrelated project plans or subsystems.

## Working method

Work through the phases in order, but respect existing completed checkpoints. If Phase 1 is already committed and verified, begin Phase 2 instead of repeating Phase 1.

For each phase:

1. inspect only relevant current code;
2. implement the phase plus the smallest prerequisite needed to compile;
3. review the diff for unrelated changes;
4. run the required build/CI gate when available;
5. commit the phase when green;
6. continue to the next phase.

Do not combine unrelated cleanup into the phase. Do not use a phase as permission to redesign adjacent systems.

If current code exposes a genuine product conflict, destructive migration risk, or requirement that cannot be satisfied while preserving load-bearing behavior, stop for owner input. Ordinary coding choices are not owner decisions.

---

# Phase 1 — Appearance foundation and legacy settings migration

## Goal

Create the Appearance destination and the preference/state layer the redesign will use, without changing message rendering yet.

## Implement

- Add **Appearance** directly beneath Images using the exact title/subtitle from `chat-redesign-plan.md`.
- Add the approved Appearance controls and name-style controls.
- Preserve Desktop Mode's stored value/behavior under **Hardware Keyboard Shortcuts**.
- Preserve the effective Hide Model Names value when converting to positive **Model names**.
- Remove the old Classic/Non-Classic UI/product concept.
- Remove Monochrome and its obsolete behavior as specified.
- Remove the standalone AMOLED settings tile while preserving AMOLED capability/wiring.
- Leave Auto-save Chats untouched.
- Add the companion AI-name override controls specified by the plan.
- Use the existing app-wide shared styles where applicable; do not duplicate the general style sheet into chat-specific resources/docs.

## Do not do yet

- no message-row redesign;
- no provider/reasoning work;
- no composer redesign;
- no image cleanup beyond a compile prerequisite.

## Verify

- migrated values are preserved;
- Appearance controls persist correctly;
- Auto-save Chats is untouched;
- AMOLED capability remains;
- no unrelated settings behavior changed.

**Checkpoint commit:** `phase4: add Appearance foundation and migrate chat display settings`

---

# Phase 2 — Current presentation shell and final geometry

## Goal

Build the single current presentation shell described in the plan without rewriting or duplicating the chat engine.

## Implement

- Keep one shared chat behavior/data system underneath presentation.
- Build one current adaptable presentation shell for AI-left and user-right messages.
- Do not build a second shell, shell selector, renderer registry, preset framework, or future shell machinery.
- Keep streaming, markdown, actions, attachments, persistence, and provider logic outside presentation-specific duplication.
- Apply the approved geometry from the plan exactly unless a small responsive correction is required.
- Implement Profile images, Names, AI bubble, and User bubble as presentation changes inside the current shell.
- Implement portrait-on and portrait-off name placement exactly as specified.
- Implement the shared Chat Name Style and approved font/size behavior.
- Preserve all existing message actions and behavior.

## Verify

Test at minimum:

- portraits on/off;
- names on/off;
- AI bubble on/off;
- User bubble on/off;
- portrait + name and portrait-off + name;
- long/short user and AI messages;
- no width/container growth from the user-only inset;
- streaming and existing actions still work.

Use geometry HTML only if needed as a visual aid. The plan's numbers are authoritative.

**Checkpoint commit:** `phase4: add current message shell and final geometry`

---

# Phase 3 — Message Details and durable per-message metadata

## Goal

Add universal Message Details and optional persistent model/token attribution tied to the correct historical messages.

## Implement

- Add `ⓘ` as the far-left Message Action for both user and AI messages.
- Implement the anchored selectable/copyable Message Details popup.
- Add durable per-message storage for actual response model identifier and provider-reported token usage where available.
- Implement independent Model names and Token usage persistent display toggles.
- Follow the placement/wrapping rules from the plan.
- Never relabel old messages from current-chat settings.
- Never invent unavailable token values.

## Verify

- switch models within one conversation and confirm old turns keep original attribution;
- toggle Model names and Token usage independently;
- reopen the conversation and confirm message ownership persists;
- `ⓘ` remains present regardless of persistent-display toggles.

**Checkpoint commit:** `phase4: add message details and per-turn model token metadata`

---

# Phase 4 — Attachments and provider-neutral visual media cleanup

## Goal

Integrate existing file/image presentation into the current shell while preserving behavior and safely removing active DALL-E-specific presentation naming.

## Implement

### File/document attachments

Apply the ordering rules in the plan without changing attachment semantics.

### Images / generated visual media

Apply provider-neutral image ordering from the plan without moving ordinary images into the file/document tray.

### Coordinated naming cleanup

- Re-verify current references only in the relevant presentation paths.
- Rename active DALL-E-specific presentation IDs/helpers/comments atomically to provider-neutral names.
- Update every required layout/adapter reference in the same change.
- Remove dead presentation branches only after targeted reference checks prove them unused.
- Preserve migration-only readers until their existing deletion gate is satisfied.

Consult image-generation status material only if needed to distinguish active presentation debt from intentional migration compatibility. Do not broaden into a general image-generation redesign.

## Verify

- existing attachment interactions still work;
- existing generated/attached image rendering still works/compiles;
- no active chat-presentation identifier remains provider-specific where the plan requires neutrality;
- migration compatibility remains safe.

**Checkpoint commit:** `phase4: integrate attachments and neutralize generated image presentation`

---

# Phase 5 — Provider-neutral Thinking/reasoning support

## Goal

Display provider-supplied reasoning/thinking content without model-name hard-coding.

## Implement

- Inspect only the provider/protocol adapters actually supported by the app.
- Verify current primary provider/API documentation for those adapters as needed.
- Normalize separately supplied reasoning/reasoning summaries into one provider-neutral per-message representation.
- Persist reasoning with the message that produced it.
- Support separately streamed reasoning when the provider exposes it.
- Render Thinking as its own movable child in the current AI presentation shell, positioned exactly as specified in the plan.
- Default collapsed; use approved label/chevron behavior.
- Do not show an empty Thinking row.
- Never expose hidden/internal reasoning that was not returned to the app.

## Verify

- focused parsing tests where supported by the existing test architecture;
- persistence/reopen;
- streaming where a supported provider supplies separate reasoning;
- ordinary responses show no empty disclosure.

**Checkpoint commit:** `phase4: add provider-neutral Thinking disclosure`

---

# Phase 6 — Multiline composer and keyboard/inset hardening

## Goal

Keep the current compact composer interaction while improving long drafting and preventing software-keyboard overlap.

## Implement

- Preserve attach/mic/send/conversation behavior, IDs, listeners, text watcher, voice state, and send semantics.
- Keep the one-line field between the existing side controls.
- Grow the text/composer upward as text wraps while controls remain bottom-anchored.
- Use the plan's maximum-height reference and internal scrolling behavior.
- Preserve hardware keyboard behavior.
- Fix software-keyboard overlap using one coherent inset mechanism; do not layer a second competing keyboard-offset hack onto the existing system.

## Verify

- one-line composer remains compact;
- multiline draft grows upward;
- very long draft scrolls internally;
- attach/mic/send and voice tinting remain intact;
- hardware keyboard behavior remains intact;
- software keyboard does not cover the composer on the test device/configurations available.

**Checkpoint commit:** `phase4: improve multiline composer and keyboard insets`

---

# Phase 6.1 — Persistent Includes access from later messages

## Goal

Restore continuous user control over documents/images that remain in conversation context without repeating the full Includes box on every later turn or duplicating attachment payloads.

## Implement

- Read Section 6.4 of `chat-redesign-plan.md` as the product contract and consult `document-includes-plan.md` only for the existing Include ladder/actions that Section 6.4 explicitly preserves.
- Keep every sent Include canonically owned by the original user message where it entered history. Do not copy an Include, its image bytes, extracted document text, condensed/reduced text, or artifact onto later messages.
- Derive which earlier Includes are represented in conversation context at each later **user** message.
- When one or more earlier Includes apply, add a paperclip Message Action immediately to the right of the universal `ⓘ` action. Do not add the inherited-context paperclip to AI messages.
- The paperclip is only an indicator/control surface. It must not alter the model-facing message list merely by being rendered.
- Tapping it opens the anchored Includes popup specified in the plan and reads the **current canonical state** of the applicable earlier Includes.
- Reuse the established Include vocabulary, state information, token/size notices, and post-send controls. Do not create a parallel Condense/Reduce/Remove implementation.
- Route every popup action back to the canonical Include on its original message. A Reduce/Condense/Remove/Edit action invoked from a later message must update that original state and refresh every derived paperclip popup that references it.
- Preserve the existing Full → Condensed/Reduced → Artifact semantics and all existing auxiliary-model, fallback, progress/error, persistence, image-byte deletion, and editing behavior.
- Keep the original attachment-bearing message's ordinary file/image presentation unchanged. If a later message also attaches something new, its own attachment presentation is separate from the inherited-context paperclip.

## Do not do

- do not stamp the same attachment onto every subsequent message;
- do not resend or duplicate image/document content solely for UI visibility;
- do not replace the existing canonical Include model with a new global attachment store unless current code proves the approved behavior cannot otherwise be implemented;
- do not redesign Condense, Reduce to Text Only, Remove, Artifact generation, or their prompts as part of this phase;
- do not fold the separate composer visual-polish idea into this repair.

## Verify

At minimum:

- send a document, then several later user turns: every later user message exposes the paperclip without receiving a copied Include record;
- repeat with a full image and with mixed document/image history;
- tapping any later paperclip shows the same current canonical state and established actions;
- Condense a document from a later message and confirm the original Include changes state while later indicators remain usable;
- Reduce an image from a later message and confirm image bytes/model-facing visual content are removed according to the existing rules without creating a new Include on the later message;
- Remove a previously sent Include from a later message and confirm the original canonical item becomes the existing Artifact rather than disappearing;
- reopen the chat and confirm derived paperclips and canonical state reconstruct correctly;
- confirm AI messages do not get the inherited-context paperclip;
- confirm no paperclip appears before the first Include exists;
- inspect request construction to prove this UI repair does not duplicate attachment payloads or move their historical position;
- test RecyclerView recycling so paperclips do not leak onto unrelated user rows.

**Checkpoint commit:** `phase4: restore persistent Includes controls on later messages`

---

# Phase 7 — Integration hardening and regression sweep

## Goal

Verify the complete redesign without reopening the design or broadening scope.

## Implement / verify

- fix the standing chat-header/title disappearance bug if still present;
- verify future drawer integration does not require rewriting message rows/chat behavior;
- test top/bottom/system-bar insets, rotation/configuration changes, large font scale, and long conversations;
- test RecyclerView reuse/recycling for stale visibility/state across portraits, names, metadata, Thinking, images, attachments, persistent Includes paperclips/popups, and Message Details;
- verify streamed responses do not inherit stale metadata/reasoning from recycled views;
- verify persistent Includes actions still mutate only their canonical original-message records and do not duplicate model-facing payloads;
- verify Appearance combinations remain readable and compatible with the existing app-wide theme/style system;
- verify no chat-specific palette system or duplicated general style sheet was introduced;
- verify Classic/Non-Classic and Monochrome are removed, AMOLED wiring remains, and Auto-save Chats is untouched;
- re-run targeted checks for coordinated generated-image renames;
- run the required final build/CI gate.

Do not turn this regression pass into a repository-wide audit or cleanup sweep.

## Completion report

Provide only:

- phase commits;
- build/CI status;
- deliberate compatibility/migration choices;
- anything that could not be runtime-tested and why;
- any genuine remaining owner decision.

**Checkpoint commit:** `phase4: harden chat redesign integration`

---

# Stop conditions

Stop for owner input only when:

1. current code makes two approved requirements mutually incompatible;
2. implementation requires a destructive data migration not already authorized;
3. a load-bearing behavior cannot be preserved without changing product design;
4. a genuinely new user-facing choice is required and the plan does not define it;
5. something marked for removal is still actively used in a way that changes the product decision.

Do **not** stop for ordinary Kotlin/XML technique, moved filenames, provider field-name differences, a needed shared resource, or small responsive corrections that preserve the approved design.

# Definition of done

The chat redesign is implementation-complete when all planned phases, including Phase 6.1, are committed and pass required build/CI checks, the single authoritative plan is satisfied, existing chat behavior is preserved, and any unperformed provider/runtime tests are documented rather than claimed as verified.
