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

# Phase 6.1 — Persistent Includes and composer workspace controls

## Goal

Restore continuous user control over documents/images that remain in conversation context, and replace the cramped composer control geometry with a bottom-row workspace plus an optional expanded drafting mode. Preserve the Phase 6 multiline/inset work rather than replacing it.

## Implement

### Persistent Includes

- Read Sections 6.4 and 9 of `chat-redesign-plan.md` as the product contract and consult `document-includes-plan.md` only for the existing Include ladder/actions that Section 6.4 explicitly preserves.
- Keep every sent Include canonically owned by the original user message where it entered history. Do not copy an Include, its image bytes, extracted document text, condensed/reduced text, or artifact onto later messages.
- Derive which earlier Includes are represented in conversation context at each later **user** message.
- When one or more earlier Includes apply, add a paperclip Message Action immediately to the right of the universal `ⓘ` action. Do not add the inherited-context paperclip to AI messages.
- The paperclip is only an indicator/control surface. It must not alter the model-facing message list merely by being rendered.
- Tapping it opens the anchored Includes popup specified in the plan and reads the **current canonical state** of the applicable earlier Includes.
- Reuse the established Include vocabulary, state information, token/size notices, and post-send controls. Do not create a parallel Condense/Reduce/Remove implementation.
- Route every popup action back to the canonical Include on its original message. A Reduce/Condense/Remove/Edit action invoked from a later message must update that original state and refresh every derived paperclip control that references it.
- Preserve the existing Full → Condensed/Reduced → Artifact semantics and all existing auxiliary-model, fallback, progress/error, persistence, image-byte deletion, and editing behavior.
- Keep the original attachment-bearing message's ordinary file/image presentation unchanged. If a later message also attaches something new, its own attachment presentation is separate from the inherited-context paperclip.

### Normal composer workspace

- Rework the normal composer into one rounded surface with the editable text region above a fixed bottom control row.
- Preserve the existing upward-growing multiline behavior from Phase 6; the normal-mode approximately `120dp` cap remains the reference and overflow scrolls internally.
- Replace the existing attachment paperclip entry point with **Add (`+`)**. It opens the existing Camera / Image / Document chooser and must reuse the existing import/send wiring.
- On the bottom row, place Add on the left. When earlier sent Includes remain represented in conversation context, place the conditional persistent-context **paperclip immediately to the right of Add**.
- The composer paperclip opens the same current-state Includes management surface as the later-message paperclip. Actions still mutate only the canonical original-message Include.
- Keep the distinction strict: Add creates new pending context; paperclip manages already-sent persistent context.
- Keep the right side ordered **Expand content → Mic → existing Send/Conversation control**.
- Preserve current pending-unsent Includes presentation and detach behavior unless the authoritative plan is later changed again; this phase does not silently redesign pending attachment rows.
- Use existing semantic/theme icon roles and maintain readable contrast; do not hard-code a composer palette.

### Expanded drafting mode

- Add **Expand content** directly to the left of Mic in normal mode.
- Expanding must resize/reparent the existing live composer/editor safely or otherwise preserve one authoritative editor state. Do **not** create a second text editor and synchronize draft strings between two editors.
- Expanded mode fills the available chat content area above the software keyboard and below the header/system-bar region while retaining the existing IME/window-inset ownership.
- In expanded mode, remove the normal approximately `120dp` cap and let the text region use the available height, scrolling only after that expanded area is exhausted.
- Keep Add, conditional persistent Includes paperclip, Mic, and Send/Conversation reachable in expanded mode.
- Hide/remove the normal Expand action while expanded and show **Collapse content in the upper-right corner** of the expanded composer surface.
- Collapse returns to the normal composer height appropriate to the current draft without clearing, truncating, sending, or moving the cursor arbitrarily.
- Expansion/collapse must preserve draft text, cursor/selection, IME composing state where Android permits, pending attachments, voice state, and current model/provider selection.
- Do not add a second inset listener/keyboard-offset scheme for expanded mode.
- Preserve hardware-keyboard behavior in both modes.

## Do not do

- do not stamp the same attachment onto every subsequent message;
- do not resend or duplicate image/document content solely for UI visibility;
- do not replace the existing canonical Include model with a new global attachment store unless current code proves the approved behavior cannot otherwise be implemented;
- do not redesign Condense, Reduce to Text Only, Remove, Artifact generation, or their prompts as part of this phase;
- do not create a second draft/editor state for expanded mode;
- do not rebuild the Phase 6 IME solution with a competing inset mechanism;
- do not change pending attachment semantics merely because the Add icon changed.

## Verify

At minimum:

### Includes

- send a document, then several later user turns: every later user message exposes the paperclip without receiving a copied Include record;
- repeat with a full image and with mixed document/image history;
- tapping any later-message or composer paperclip shows the same current canonical state and established actions;
- Condense a document from the composer or a later message and confirm the original Include changes state while all derived indicators remain usable;
- Reduce an image from the composer or a later message and confirm image bytes/model-facing visual content are removed according to the existing rules without creating a new Include on the later message;
- Remove a previously sent Include from a later-message/composer popup and confirm the original canonical item becomes the existing Artifact rather than disappearing;
- reopen the chat and confirm derived paperclips and canonical state reconstruct correctly;
- confirm AI messages do not get the inherited-context paperclip;
- confirm the composer paperclip is absent before any earlier sent Include exists and appears when persistent context exists;
- inspect request construction to prove this UI repair does not duplicate attachment payloads or move their historical position;
- test RecyclerView recycling so paperclips do not leak onto unrelated user rows.

### Composer

- normal empty/one-line composer shows text above the bottom row rather than squeezed between controls;
- Add opens the existing Camera / Image / Document chooser and existing pending attachment behavior still works;
- normal multiline drafting still grows upward and eventually scrolls internally;
- conditional composer paperclip appears/disappears from actual persistent-context state without shifting or corrupting Add behavior;
- Expand opens the same live draft into the available chat area above the keyboard;
- text, selection/cursor, pending attachments, and control state survive expand → edit → collapse cycles;
- Collapse is available in the expanded surface's upper-right corner and returns to the correct normal height for the current draft;
- very long expanded drafts scroll internally without moving the composer under the keyboard;
- Add / paperclip / Mic / Send/Conversation remain usable while expanded;
- hardware keyboard and software keyboard both behave correctly in normal and expanded modes;
- rotation/configuration changes and RecyclerView/layout updates do not leave the composer stuck in contradictory normal/expanded geometry.

**Checkpoint commit:** `phase4: restore Includes controls and add composer workspace modes`

---

# Phase 6.2 — Summarizer-safe persistent Includes projection

## Goal

Make the conversation Summarizer and persistent Includes coexist without silently summarizing, dropping, duplicating, moving, or changing attachment state. Preserve stable prompt-prefix eligibility where the provider supports caching.

## Implement

- Read Section 6.5 of `chat-redesign-plan.md` as the product contract. This is request architecture, not a UI redesign.
- Keep canonical Include ownership on the original user message. Do not migrate attachment storage into a new global owner merely because the model-facing projection is split.
- When Summarizer transmission is active, build two parallel model-facing layers from canonical chat state:
  - a **persistent Include layer** containing each sent Include's current model-facing payload exactly once; and
  - a **conversation layer** containing conversation text, rolling summary/recent history, and stable references to Includes rather than duplicate Include payloads.
- Apply the split immediately when Summarizer transmission becomes active, including first enable on an existing long chat and during catch-up. Do not wait for an attachment's origin message to cross the fold bookmark before moving its model-facing payload into the persistent layer.
- Give the conversation Summarizer message text plus minimal stable attachment references only. Do not feed it Full document text, Full image bytes, Condensed document payloads, Reduced image descriptions, or Artifact payload text.
- The conversation Summarizer may summarize discussion *about* an attachment, but it must never perform an Include transformation or cause `FULL → CONDENSED`, `FULL → REDUCED`, `→ ARTIFACT`, deletion, or disappearance.
- Preserve user-origin authority. Relocating an Include for transmission/cache structure must not promote user-supplied attachment content into system/developer authority.
- Keep persistent Include units in original activation/message order. New Includes append after existing units. Condense/Reduce/Remove/Edit changes replace that Include's payload in the same logical slot rather than reordering it.
- Keep serialization deterministic. Preserve the existing stable Include rendering rules and avoid volatile material before stable Include payloads: no changing attachment-count manifest, timestamps, request-time metadata, changing signed URLs, nondeterministic ordering, or per-request reformatting.
- Reuse stored document representations and stored image bytes. Do not re-extract documents, recompress/resize images, or regenerate Condensed/Reduced/Artifact text merely to build another request.
- In regular Summarizer requests, keep the stable system/persona prefix before the persistent Include layer, and keep persistent Includes before the rolling summary and retained conversation. Preserve the existing cache-friendly placement of genuinely dynamic per-turn injections near the newest turn rather than moving them ahead of stable history.
- When Summarizer transmission is off, preserve the existing full-history inline-Include projection. Turning Summarizer on/off is an intentional request-shape change and may invalidate provider cache; do not distort ordinary non-Summarizer chats merely to avoid that one transition.
- Make context-window/token capacity measurement use the **exact same outbound projection** that the request will send, including persistent Includes exactly once. Do not count a payload twice or omit it from capacity checks.
- Apply equivalent projection behavior to every regular chat request path that can use Summarizer transmission, including the frozen normal-send path and legacy/retry/voice path. Prefer one shared projection builder where practical; if implementation stays split, parity tests are mandatory.
- Treat prompt caching as an optimization target, not a promise: preserve deterministic stable-prefix eligibility, but never claim a routed provider/model will actually cache a document or image.

## Do not do

- do not let the conversation Summarizer absorb Include payloads into the rolling summary;
- do not duplicate an Include payload in both its origin/recent message and the persistent layer;
- do not silently drop a Full image or document when its origin message is folded;
- do not create automatic Condense/Reduce/Artifact behavior to solve context pressure in this phase;
- do not add a mutable active-attachment count/manifest ahead of stable persistent Include units;
- do not promote attachment content to system/developer authority;
- do not rewrite unrelated memory/lore/provider request ordering.

## Verify

At minimum:

- Full document survives its origin message crossing the fold boundary and remains Full until the user explicitly changes it;
- Full image survives the fold as an actual model-facing image part and is not silently dropped;
- rolling-summary updates do not reorder or mutate persistent Include units before the changed summary suffix;
- the conversation Summarizer receives stable attachment references but no attachment payloads;
- first enabling Summarizer on an old attachment-heavy chat produces exactly one payload per active Include during catch-up, with no temporary duplication;
- widening/narrowing Complete Messages changes conversation membership without moving or duplicating persistent Includes;
- toggle Summarizer off/on and confirm canonical ownership/state survives and each mode uses its approved projection;
- Condense, Reduce, Remove-to-Artifact, and Edit after the origin message is already folded update the same persistent unit and future requests use only the new current form;
- several Includes on one message and Includes across several messages retain stable original activation order;
- capacity/token measurement matches the exact serialized outbound projection and counts persistent payloads once;
- frozen typed Send and legacy/retry/voice paths produce equivalent Include/Summarizer projection semantics;
- serialized-prefix regression coverage proves that ordinary fold progress or summary catch-up cannot rewrite content preceding the rolling summary unless the user intentionally changes an earlier Include or other earlier stable context;
- no UI paperclip can claim a Full Include is active while the corresponding request path has silently omitted its payload.

**Checkpoint commit:** `phase4: separate Summarizer conversation and persistent Include projections`

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
- verify Summarizer mode preserves persistent Includes independently of conversation folding and never silently transforms/drops attachment state;
- verify normal/expanded composer transitions preserve draft state and do not introduce a second keyboard/inset owner;
- verify Add, persistent-context paperclip, Mic, and Send/Conversation remain theme-readable and functionally distinct;
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

The chat redesign is implementation-complete when all planned phases, including Phases 6.1 and 6.2, are committed and pass required build/CI checks, the single authoritative plan is satisfied, existing chat behavior is preserved, and any unperformed provider/runtime tests are documented rather than claimed as verified.