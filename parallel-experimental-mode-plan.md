# Parallel Experimental Mode — Staged Implementation Plan

**Status:** Product direction established; implementation not started by this document.  
**Source of truth for implementation:** Current `origin/main` and its tests.  
**Integration target:** `main`, which builds the **Beta** client (not the retired pre-release client).  
**Purpose:** Deliver independent, simultaneous responses from 2–4 preselected AI models in a familiar, tabbed chat UI, without corrupting conversation history or disrupting existing single-response chat.

This is a staged roadmap, **not** permission to silently change undecided product behavior. Implement only the assigned stage and necessary dependencies. Record what was actually completed, tested, and merged. Refer to `CLAUDE.md` for repository rules.

## 1. Finished-product definition

A finished implementation lets someone enter **Experimental Mode Setup**, choose **Single Response** or **Parallel Responses**, select **2–4 models** for parallel mode using the existing model picker in multi-select mode, designate a **primary model**, and optionally turn **Last Message Only** on or off.

In a parallel turn, all selected models independently answer the **same user message** using their own model/provider settings and the same eligible starting context. One visible assistant response group appears, with tabs across the top. Tapping a tab brings that model's response to the foreground. The **starred model** identifies which answer is canonical for continued conversation; browsing another tab by itself does not silently change that canonical response. A clear, explicit **Make Primary** action can change it under the existing conversation-branch safety rules. Each model can be regenerated without regenerating the other models.

The responses, model identities, primary selection, per-model regeneration history, completion/failure state, usage evidence, and conversation branch survive navigating away, reopening the chat, app restart, and supported backup/restore. Later turns never accidentally include competing models' responses in the context sent to another AI.

**Not finished:** Merely drawing tabs, returning four responses in memory, or displaying a success while responses disappear on process death. No partially built mode should be exposed as working.

## 2. Product contract agreed so far

### Experimental Mode / Quick Settings

- Experimental Mode is **per chat**; new chats start with it off. Existing chats retain their existing setting after upgrade.
- Replace the present standalone Experimental Mode toggle in Quick Settings with an **Experimental Mode** row and **Setup** button (or equivalent clear entry point), plus concise active-mode status.
- Setup controls whether Experimental Mode is **Off**, **Single Response**, or **Parallel Responses**. The underlying choices are **response count** and **conversation context**, not separate incompatible engines.
- **Last Message Only** is an independent toggle shown when Experimental Mode is enabled. With it **on**, retain today's experimental behavior: send the latest user message plus the same fresh-chat instructions, persona, memories, lorebooks, model rules, and current-turn attachments as before, without prior conversation turns or summaries. With it **off**, send the appropriate existing conversation history.
- Switching Experimental Mode off restores ordinary full-history behavior, just as the current toggle does. Do not permanently erase earlier messages or summaries when changing context mode.
- **Single Response** keeps the normal one-model picker and familiar existing chat UI. **Parallel Responses** replaces/hides that picker in Quick Settings with a compact selected-model count/summary and a route back to Setup. Do not cram four independent pickers into Quick Settings.
- Per-chat setup persists. Users **do not** reselect the models on every message or every visit. They can reopen Setup to swap models for subsequent turns; older response groups retain their original model lists.
- Multiple selection is built from the **existing advanced model picker**: preserve search, providers, favorites, recents, and model-specific configuration. Choose **2–4** distinct model assignments; the UI cap is four, but avoid hardcoding four through unrelated storage/generation layers. No send if a required model/primary assignment is missing.
- Parallel Responses is **not compatible with hands-free mode** in this initial release. Normal hands-free and single-response behavior must not regress.

### Response groups, tabs, and branching

- A parallel response group belongs to **one user turn** and contains one independently stored response slot for each assigned model. Historical groups are immutable with respect to later Setup changes.
- Tabs show recognizable short model names; full identifiers remain available in message details/accessibility. Use an accessible selected-tab indication and show individual pending, completed, canceled, and failed states.
- A **star next to the primary model name** denotes the canonical response. Use the existing subtitle color role `?attr/appRowSubtitleColor` (the `Widget.App.Row.Subtitle` role); do not invent a hardcoded gold/yellow. Active/selected tab styling follows existing shared styles.
- Tapping a tab **only changes the foreground answer**. The initial foreground answer is the primary; subsequent browsing does not change the canonical history by accident.
- Provide an explicit **Make Primary** affordance for choosing a different answer. On the latest turn, promote without deleting anything. On an earlier turn with descendants, respect the existing confirmation/truncation and summary-invalidating rules; never silently rewrite the branch or lose the old answers. Final wording/placement should be previewed before its UI stage.
- In full-history mode, the next request sees only the chosen canonical response from each previous turn, not its sibling alternatives. In Last Message Only mode, prior replies and summaries are excluded regardless of which model was starred; primary selection remains durable so turning the toggle off later is coherent.
- Regeneration is **per model slot**: regenerate only that model with the original turn's correct prompt/context and its model settings, preserve its prior versions and charges, leave siblings untouched, and preserve canonical branch semantics. Reuse the existing version mechanism where safe, but do not flatten independent models and per-model regenerations into an ambiguous one-dimensional version list.
- Existing ordinary one-model regeneration, version navigation, copy, bookmarking, search, manual branch changes, attachments, and chat behavior continue to work.

### Isolation and request ownership

- Snapshot the input and relevant context at turn dispatch. Each model gets that same eligible turn context plus its own model-specific request configuration; no response, reasoning, error, or partial stream from a sibling enters another's prompt.
- Request streams, response buffers, tool/diagnostic callbacks, pricing/usage capture, stop/failure state, and persistence ownership are **per request**, not shared mutable “current model/current assistant” fields.
- A slow, throttled, canceled, or failed model cannot overwrite, cancel, hide, or prevent persistence of successful sibling results.
- One model's response may arrive before others; show its progress independently. Re-entry/restart must reconcile unfinished attempts honestly without treating blank/unfinished responses as successful.
- Parallel calls may cost up to four separate billable requests. Do not make hidden extra model calls for comparison, ranking, synthesis, or voting.

### Usage & Cost

- **Reuse** existing request-level accounting and Usage & Cost screens where correct; a new usage dashboard is **out of scope** for the first release.
- Capture and retain any provider usage/cost evidence **for each request**, even when display support is deferred. Include reported usage on failed/canceled requests when provided, and do not estimate unknown charges as zero.
- Existing `UsageLogStore` serializes log updates, and `TokenUsageAccounting` groups by model/provider, but this **does not prove** current chat generation's shared pending usage/pricing fields are parallel-safe.
- Validate the actual persisted request identity/deduplication path. `ProviderUsageAttempt.attemptId` exists, but do not assume ordinary chat `TurnUsageRecord.requestId` is populated: inspect the record construction path.
- Show parallel usage in the existing screen only if tested accurate. Otherwise label **parallel-response usage statistics unavailable** in that UI while preserving individual durable records and leaving normal usage unaffected. No broad usage rewrite solely for this feature.
- Any new persistent diagnostic/logging lines require owner approval under `CLAUDE.md`; recording already-approved usage evidence is not a license to expand logging.

## 3. Current-code anchors to inspect

These are starting points on `main`, **not** blanket instructions to reuse their representations unchanged:

- `app/src/main/java/org/teslasoft/assistant/conversation/ExperimentalMode.kt`: current last-user-only projection, with fresh-chat content preserved.
- `app/src/main/res/layout/view_quick_settings_experimental_block.xml` and Quick Settings integration in `ChatActivity.kt`: current per-chat toggle, to replace with Setup entry.
- `app/src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AdvancedModelSelectorDialogFragment.kt` and picker adapters: current single-model picker.
- `app/src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt`: `variants`, `canonicalVariant`, `displayVariant`, snapshot/apply behavior and version UI. Display and canonical are already **separate** today.
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt`: dispatch, send/regenerate, lifecycle, request context, canonical projections, summaries, branch promotions, pending usages, hands-free guards, and storage integration. This is large: build new cohesive helpers in separate files with minimal glue.
- `app/src/main/java/org/teslasoft/assistant/ui/chat/RegenerationRecovery.kt`: interrupted retry reconciliation.
- `app/src/main/java/org/teslasoft/assistant/preferences/chatstore/ChatStore.kt`: encrypted per-message persistence; understand rename, replace, truncation, and restore implications.
- `app/src/main/java/org/teslasoft/assistant/preferences/PerChatSettingKeys.kt`: register any new per-chat settings.
- `app/src/main/java/org/teslasoft/assistant/usage/{ProviderUsageAttempt,TokenUsageAccounting,UsageLog}.kt`: request evidence, record aggregation, persisted log.
- `chat-storage-and-backup-plan.md`, `usage-and-cost.md`, and current tests: integration/compatibility constraints. Inspect current code before relying on older documents.

**Observed architecture hazard:** `ChatActivity.kt` already carries a coroutine-propagated `GenerationRequestContext`, but several code paths still rely on mutable activity-wide fields such as `currentProviderUsageAttempt`, pending provider usage/pricing snapshots, the last assistant message, and pending regeneration. These cannot simply be run four times concurrently. Build and test isolation first.

## 4. Implementation milestones

Each stage has its own acceptance criteria. A stage is complete only when its required implementation and tests are merged to `main`. Do not expose a half-working Parallel Responses option in the beta UI.

### Stage 0 — Read-only integration audit and concrete design contract

**Deliverable:** Short technical mapping in this file or the assigned PR/issue of the exact turn lifecycle, chat persistence schema, picker APIs, and affected tests. No product UI changes.

1. Trace request dispatch through streaming/finalization, generation service/background survival, usage, save/reload, and per-message/version projection.
2. Trace canonical vs displayed versions, earlier-turn branch promotion, regeneration recovery, summarizer/compaction, attachment handling, search/index, and backup/import/export.
3. Choose a backward-compatible response-group representation with stable IDs: group/turn ID, ordered model slots, model/provider/endpoint configuration snapshot, primary slot ID, foreground slot ID, per-slot request attempts/version history, completion/error state, and accounting ownership. Document which fields are persistent and which are transient.
4. Prove exactly how preexisting Experimental Mode booleans map to the new setup without changing existing conversations: **off → Off**; **on → Single Response with Last Message Only enabled**. New chats default Off.
5. Identify which interactive completion/tool/image paths are in scope on day one versus needing an explicitly approved compatibility rule. **Never silently drop a previously supported current-turn attachment**.
6. Identify any necessary owner decisions in §6 before work that depends on them.

**Exit:** Data contract, migration/backward compatibility, prompt isolation contract, and test plan are reviewable. No unanswered design choice is hidden inside production defaults.

### Stage 1 — Durable response-group foundation (not user-visible)

**Deliverable:** Backward-compatible storage and model code, plus tests; parallel UI remains unavailable.

1. Implement response-group/slot identity, immutable per-turn model assignments, canonical-vs-displayed selection, per-model regeneration version history, serialization/deserialization, and invalid/corrupted metadata recovery.
2. Preserve legacy ordinary messages and existing regenerated variants byte-for-byte in behavior; migrate lazily or additively only if shown safe. Do not mutate unrelated conversations.
3. Persist and load response groups safely through chat rename, encrypted storage, export/restore, backups, message deletion, branching/truncation, and settings copy.
4. Ensure model-facing conversation projections read only the canonical answer. Hidden alternatives are stored but never inadvertently inserted as sequential assistant messages. Verify downstream context consumers including summarizer, memory recall/indexing, and export semantics.
5. Add unit/instrumentation tests covering old chat migration, multi-group round trip, partial slots, primary changes, restart/recovery, history truncation, and backup fidelity.

**Exit:** Can store/reload/restore a complete synthetic four-model turn with its alternatives and per-slot versions, without any new UI or network dispatch.

### Stage 2 — Parallel request coordinator (hidden until usable)

**Deliverable:** Independently owned 2–4 request orchestration and reliable persistence, exercised by deterministic fakes.

1. Capture eligible prompt and attachment snapshot once, then dispatch each selected model concurrently with its own endpoint/routing/parameters, stream, cancellation, usage attempt, pricing lookup, diagnostic state, and completion outcome.
2. Avoid shared `current...` state: per-slot finalization must attach to the **correct response slot**, never “last assistant message.”
3. Persist each result as it settles. Define interruption/process-death recovery before enabling the mode.
4. Implement individual regeneration without resending siblings, and preserve usage for all attempts including provider-reported charges on failures.
5. Test out-of-order completion, different provider endpoints, identical names with different identities, one failure, all failures, cancellation, retry, slow request, process death/reopen, storage failure, malformed responses, pricing/usage race, and no cross-model prompt leakage.
6. Verify the current Usage & Cost display with several simultaneous requests. If it cannot represent them correctly, retain raw records and mark parallel totals unavailable only where necessary.

**Exit:** Fake and controlled live/provider integration tests demonstrate four independent requests with correct identity, saved results, usable recoverability, and no sibling contamination. Do not expose the mode until this passes.

### Stage 3 — Experimental Mode Setup and model selection

**Deliverable:** New setup UX based on existing picker, wired to tested configuration state.

1. Replace the current Quick Settings Experimental toggle with **Experimental Mode / Setup** and active status, while preserving the old setting's meaning for existing chats.
2. Provide Off / Single / Parallel choice; show **Last Message Only** when experiment mode is on.
3. Add multi-select picker behavior (2–4 assignments) to the existing picker without altering ordinary single-select behavior. Persist model order, full model/endpoint identities, and primary choice per chat.
4. In parallel mode, replace the standard single-model Quick Settings control with a compact model summary and setup access. On returning to single/off, restore the usual picker.
5. Changing model assignments affects future turns only; never relabel historical tabs. Handle invalid/deleted models and switching active provider gracefully without quietly changing the selected primary.
6. Prevent hands-free engagement while Parallel Responses is active; changing modes must leave existing recorded messages safe.

**Exit:** Configuration survives navigating away/restart, normal picker still works, old Experiment chats keep their old behavior, and selected models are correct before dispatch.

### Stage 4 — Tabbed chat UI, starring, and per-model controls

**Deliverable:** Complete user-facing response group.

1. Render one assistant group per user message with up to four tabs above it. Tabs show model names, loading/completion/failure, and a **star on the primary** using `?attr/appRowSubtitleColor`.
2. Tapping tabs brings the chosen response to the foreground, with the same normal message rendering and appropriate controls. Preserve tab choice while navigating within the chat.
3. Provide explicit Make Primary, with existing latest-turn vs earlier-branch protections. Browsing a tab must not promote it.
4. Per-tab regeneration applies only to that model and exposes that model's prior regenerations without mixing it up with other models' tabs.
5. Ensure accessibility, readable labels, safe truncation, dynamic themes, horizontal space on narrow phones, loading states, errors, copy/share, and ordinary response action controls. Do not use Snackbars as the only way to disclose significant response failures.
6. Integrate state restoration and update animations without jumping scroll position or displaying another model's partial text.

**Exit:** A complete real-world text conversation can compare four replies, regenerate one, designate a primary, continue, reopen, and still see the same results.

### Stage 5 — System-wide verification and beta release

**Deliverable:** End-to-end validated beta with no known critical data-loss or cross-request isolation bugs.

1. Validate all four context/response combinations: single + full history, single + last-message-only, parallel + full history, parallel + last-message-only, plus normal mode with Experimental Mode Off.
2. Verify switching Experimental Mode off after an experimental conversation restores full prior history; turning it back on preserves the stored setup and former response groups.
3. Test older chats, very long replies, model swaps between turns, provider configuration changes, alternate responses with reasoning, failed/canceled attempts, backgrounding, rotation, crash/process death, low memory, and constrained network.
4. Exercise response promotion on old turns, summarizer/compaction state, history deletion, search, chat exports, recovery backups, restore/merge, and usage/accounting under concurrent traffic.
5. Confirm only the chosen canonical branch is sent on subsequent full-history turns; no sibling model content can enter another request or memory capture by accident.
6. Confirm single-response hands-free remains functional and parallel mode cannot enter it. Verify cost display is accurate or explicitly unavailable for parallel requests, never understated by omission.
7. Follow repository checks and code review gates on the final commit. Build **`assembleBeta`** and confirm beta application identity; do not ship the retired pre-release variant.

**Exit / Definition of Done:** The mode is usable rather than merely visible, existing behavior is intact, chat/backup data is preserved, usage truthfulness is tested, code-changing PRs have cleared Codex and Android Checks, and `main` contains the completed feature. Device-specific polish remains an explicit final acceptance step, not a substitute for tests.

## 5. Suggested PR boundaries and reporting

Prefer **small coherent PRs** aligned to milestones instead of one enormous risky PR. The storage and request engine stages can land unexposed behind existing behavior; combine stages only if dependencies make a coherent reviewable unit. Do not merge a PR that makes a broken or unreachable user-visible mode appear complete. Follow `CLAUDE.md`'s one-PR-per-session and validation workflow.

For each assigned stage, record: **status (not started / in progress / merged)**, actual commit/PR link, behavior added, tests run and results, known limitations, and next stage. Never mark a stage complete from an intention or a passing unit test alone. Codex review is for PRs containing real application/test code, **not documentation-only PRs**.

**Recommended start:** Stage 0, then Stage 1. Store a durable schema before parallelizing `ChatActivity`, then make requests safe before exposing the setup and tabs.

## 6. Explicit product decisions to settle at the relevant stage

These details are **not permission for an implementer to invent user-visible behavior**. They do not block starting Stage 0/1; bring a short concrete recommendation when the specific stage needs them:

1. **Primary selection control:** Star marks the primary; tapping a tab is browse-only. Decide final placement/gesture for “Make Primary” on the tab/response UI (must be discoverable and accessible).
2. **Send/Stop while a response group is still running:** Should Send wait until all model slots settle, and does Stop cancel one slot or the whole group? Make this explicit before implementing network-state UI.
3. **Model unavailability after setup:** Exact visible handling when a saved selection is removed or provider/endpoint credentials change. No silent model substitution or automatic changing of the primary.
4. **Non-text feature compatibility:** Current-turn attachments and existing request preparation must be preserved. Review tool use, image generation, and other special response paths against concurrent execution. If any supported functionality cannot safely run in parallel in v1, seek explicit owner approval for its precise behavior and user-facing message.
5. **Parallel-mode entrance while hands-free is active:** Choose a safe, clear user-facing transition that does not discard an ongoing recorded or transcribed message.
6. **Usage presentation fallback:** Only if existing usage aggregation is incorrect for parallel activity, decide the minimal accurate wording/placement; never hide known paid consumption without explanation.

These are narrow checkpoints, **not** a reason to repeatedly seek owner decisions about Kotlin helpers, internal test organization, or other reversible engineering details.

## 7. Guardrails

- All work based on **`origin/main`**, targeted to **`main`**. Do not use `beta/new-client`; it is not the current integration source even if repository metadata still names it the default.
- Preserve **Beta** build/package identity throughout development.
- Follow established shared design styles and theme roles. Avoid a parallel UI that invents a separate color system.
- No destructive migration, logging expansion, or unrelated refactor without its required approval. Preserve existing chat safety, backups, summary behavior, model attribution, and hands-free.
- Do not reinterpret an existing canonical response's variants as competing models without an explicit durable identity mapping.
- This specification is the desired destination and staged order; **current code and verified tests** establish what is already implemented.

---

### Implementation progress ledger

| Stage | Status | PR / commit | Verification / notes |
| --- | --- | --- | --- |
| 0. Integration audit and contract | Not started | | |
| 1. Durable response-group foundation | Not started | | |
| 2. Parallel request coordinator | Not started | | |
| 3. Setup and model picker | Not started | | |
| 4. Tabs, primary, regeneration UI | Not started | | |
| 5. System verification and beta release | Not started | | |

**When handing this to Work or another coding agent:** Read `CLAUDE.md`, this file, the code on current `main`, and relevant tests. Work on the **assigned stage only**, do not treat future-stage proposals as implemented, and keep this ledger accurate after each merged stage.
