# Phase 8 Beta Regression Audit and Repair Plan

**Branch:** `agent/phase-8-pre-main-safety`  
**Do not merge to `main` from this document.**  
**Audit baseline:** branch head `9c68ca9d`; `main`/merge base `0d16b3c3`  
**Scope at baseline:** 53 commits; 192 changed files overall; 114 changed production files (63 added, 49 modified, 2 deleted)

## Purpose and guardrails

This is a safety audit for the Phase 8 beta, not permission to broadly redesign the app. It records what is confirmed, what is only a plausible consequence, and what still needs runtime reproduction. Repairs are divided into three categories:

1. **Approved repairs:** a concrete defect and a narrow correction are established.
2. **Investigate/reproduce first:** the reported symptom is real, but the inspected code does not yet prove the failing state transition.
3. **Needs owner decision:** existing specifications or historical behavior conflict with the requested behavior.

No wholesale revert is recommended. `main` remains the stable comparison point, and repairs should remain on `agent/phase-8-pre-main-safety` until the owner has reviewed this report and exercised a new beta.

## Executive diagnosis

The beta has a severe shared storage-contract failure plus several independent regressions or pre-existing defects.

The repeated generic **“Sorry, action failed”** toast is most consistently explained by the new drawer/navigation repository serializing private wrapper fields through Gson reflection in an app where both debug and beta are minified. R8 can remove fields that exist only for reflection. The current ProGuard rules protect the obsolete package `com.teslasoft.assistant.**`, while the application package is `org.teslasoft.assistant.**`; the `SerializedName` rule does not protect these unannotated wrappers. A nominally successful write can therefore become `{}`. The repository subsequently rejects that object as corrupt.

That single unreadable navigation snapshot is consulted during launch, resume, drawer refresh, New Chat flows, and chat-menu construction. It therefore explains why unrelated-looking operations produce the same toast and why **Pin / Export / Delete** disappear from the three-dot chat menu. An invalid API key cannot cause a local storage failure at app launch.

The same reflection pattern exists in the encrypted deletion recovery journal. That is a separate data-safety defect: a delete operation could appear journaled even though only `{}` was stored, leaving recovery unable to identify or finish the interrupted deletion.

Other reports do not all have the same cause. Endpoint/model synchronization is an older two-source-of-truth problem, token metadata can be clipped by its layout constraints, and full-width drawer behavior conflicts with `DrawerLayout`'s normal reserved margin. Companion selection/prompt behavior and transcription controls need focused runtime reproduction before their intact logic is changed.

## Findings

| ID | Area | Finding | Confidence | Action class |
|---|---|---|---|---|
| F1 | Launch/drawer/generic toast | Folder catalog may serialize as `{}` under R8, then every navigation snapshot is rejected | High | Approved repair |
| F2 | Delete recovery | Deletion journal uses the same unsafe reflection-only wrapper | High | Approved repair |
| F3 | Drawer geometry/state | Standard `DrawerLayout` reserves a visible margin; approved spec requires 100% available width and state-preserving chevrons | High for width; runtime check for state | Investigate then narrow repair |
| F4 | Endpoint/model | Endpoint editor saves one model while the active chat retains its previous per-chat model (commonly `gpt-4o`) | High | Approved repair |
| F5 | Companion identity/prompt | Prompt assembly still exists in both normal request paths; persistence/selection failure is not yet localized | Medium | Reproduce first |
| F6 | Message token line | Metadata view can measure wider than the bubble because it lacks an end constraint, clipping instead of wrapping | High | Approved repair |
| F7 | Chat overflow menu | Menu hides saved-chat actions when navigation snapshot fails; actions themselves still exist | High | Re-test after F1 |
| F8 | Profile image shape | Default is coded as Circle and principal portraits use the shape binder; gallery/history thumbnails intentionally stayed square under an older decision | Mixed | Needs owner decision after inventory |
| F9 | Transcription | Start handlers still set Listening/stop state, but engine transition/cancellation can reset controls; exact failing engine/state is unknown | Medium | Reproduce first |
| F10 | Historical source loss | One intermediate commit gutted ChatActivity/strings, then a later commit restored them; final inventory does not show unexplained wholesale loss | High | No revert; regression test surfaces |

## Detailed evidence and repair boundaries

### F1 — Navigation storage failure causes the repeating toast

Relevant implementation:

- `ChatNavigationRepository` stored `FolderCatalog(version, folders)` using `Gson().toJson(...)`.
- The wrapper was private and its fields were neither accessed directly nor annotated for serialization.
- `app/build.gradle.kts` minifies both debug and beta.
- `app/proguard-rules.pro` contains legacy `com.teslasoft.assistant.**` keep rules rather than rules for the real `org.teslasoft.assistant.**` namespace.
- The reader manually requires literal `version` and `folders` keys, so `{}` is classified as corrupt.
- `ChatDrawerController.refresh()` surfaces snapshot failure with the generic toast.

Why it appears during many actions:

- ordinary app entry initializes/refreshed drawer state;
- opening the drawer refreshes it;
- returning from another activity can refresh on resume;
- New Chat returns through navigation state;
- the overflow menu asks the navigation snapshot whether the current chat is saved.

Approved repair:

- encode the persisted folder JSON explicitly, without reflection-only wrapper fields;
- recognize only the known empty-object shrinker artifact when the matching schema marker proves it came from the installed schema;
- preserve the original `{}` through the existing corruption-backup path before replacing it with an explicit valid empty catalog;
- keep arbitrary malformed JSON or an unmarked `{}` blocked and untouched.

This is deliberately not a general “erase corrupt storage” policy.

### F2 — What the deletion recovery journal is

Chat/image deletion spans more than one storage operation. The recovery journal is an encrypted, minimal list of stable chat/folder/image identities and the stage reached. If the app is interrupted between deleting metadata and cleaning image files, the next run can safely finish only the recorded work.

The journal used the same reflection-only Gson wrapper as the folder catalog. If it stored `{}`, there are no identities from which any deletion can safely be reconstructed.

Approved repair:

- write the journal with explicit JSON keys;
- continue reading the existing schema and field names;
- treat arbitrary malformed or unsupported journal data as unavailable and do not overwrite it;
- for the exact legacy `{}` artifact, preserve the exact bytes in the same encrypted preferences, clear only the unusable active slot, and resume with no invented deletion work.

This recovery choice favors not deleting data when the broken journal contains no trustworthy target identities.

### F3 — Drawer width and state rules

`drawer-design-spec.md` is explicit:

- the drawer occupies 100% of available screen width;
- only the approved double chevrons open/close it;
- no edge-swipe opening;
- closing/back must return to the same underlying saved or blank chat;
- mic, draft, IME, streaming, attachments, model/provider, mode, and per-chat preferences must not change merely because the drawer opened or closed.

The current drawer panel requests `match_parent`, but AndroidX `DrawerLayout` normally measures drawers with a reserved minimum margin. That can leave a strip visible despite the XML width.

The storage failure must be repaired first because it contaminates every drawer interaction. Then test the rules above as a state matrix. Change geometry/state code only where a rule demonstrably fails; do not replace the chevrons, move the composer, or redesign the drawer.

### F4 — Endpoint editor and Quick Settings disagree about the model

There are two persisted values:

- an API endpoint profile contains its own model;
- each chat has an active per-chat model used for requests and shown in Quick Settings.

The endpoint editor returns only the endpoint ID. Quick Settings reloaded the endpoint label but did not copy the endpoint's saved model into the active chat. Requests therefore continued using the stale chat value, often the legacy `gpt-4o` default. This behavior also exists on `main`; Phase 8 made it more visible but did not originate every part of it.

Approved repair for the reported flow:

- after an endpoint is saved/selected from a chat, adopt its nonblank model as that chat's active model;
- update the Quick Settings label immediately;
- retain the existing force-refresh path so the request client reloads the persisted values;
- refresh provider-mode and reasoning capability displays against the new endpoint/model pair.

This does not globally rewrite every chat when an endpoint profile is edited elsewhere.

### F5 — Companion shows “No Companion” or appears not to receive its prompt

The normal request builders still assemble the companion/persona prompt before the ordinary system prompt. No direct removal of those prompt calls was found. New provisional chat setup intentionally clears stale identity before seeding the last successful companion or the first available one.

That leaves several possible runtime faults: failure to persist the selected ID, a stale/deleted ID, timing while a provisional chat becomes saved, or UI label refresh that does not match request state.

Do not rewrite companion prompt composition based only on conversational tone. First capture, for one reproducible chat:

- companion ID before opening Quick Settings;
- displayed companion after opening;
- companion ID at request assembly;
- whether the companion prompt block is present in the locally assembled request;
- the ID after activity recreation.

Then repair the first transition where the stable ID is lost. Do not log prompt text or conversation content.

### F6 — Model/token metadata does not wrap

`MessageMetadataView` already contains the intended behavior: if model plus token usage cannot fit on one line, it renders tokens on a second line. The assistant layout gave that view `wrap_content` with only a start constraint, allowing it to measure beyond the usable bubble width and be clipped before its wrapping threshold was meaningful.

Approved repair:

- constrain metadata from start to end and use constraint width (`0dp`), giving the custom view the real available width;
- keep reasoning-token reporting in Message Details unchanged;
- do not move reasoning tokens into the top-line token summary, because hidden reasoning can still be inspected through the existing details behavior.

### F7 — Export Chat and Delete disappeared

The action implementations and labels still exist. The overflow menu conditionally includes saved-chat actions only after it can establish that the current chat is present in the navigation snapshot. When F1 makes that snapshot fail, the code falls back to a minimal menu, which is why only Logs may remain.

Re-test after F1 before modifying menu composition. If the actions remain missing with a healthy snapshot, compare saved-chat identity detection against `main`; do not duplicate delete/export implementations.

### F8 — Default profile shape and which images it affects

The code default is Circle. Uploaded user and companion portraits in the main chat renderer pass through the shape binder. However, an older documented product decision kept some gallery/history thumbnails square even when a profile image's selected shape was different. The current report says every profile representation should follow the chosen shape.

This is a genuine scope conflict, not a safe assumption. Inventory every surface (chat portrait, drawer row, Quick Settings, character/persona lists, generated-image gallery, selectors, details) and show the owner which thumbnails are currently intentionally square. Apply one owner-approved rule consistently rather than partially changing only one image.

### F9 — Transcription control does not enter/leave a stoppable state

The inspected start handlers still change the label to Listening and swap to the stop icon. During engine work, other paths disable/reset the microphone and use a separate progress cancellation control. The report could be Google dictation, cloud transcription, local Whisper, a permission transition, or a lifecycle race; each has different ownership.

Required reproduction matrix:

| Dimension | Values |
|---|---|
| Engine | Google dictation / cloud / local Whisper |
| Permission | already granted / first grant / denied |
| Chat | blank provisional / saved |
| Exit | mic stop / progress cancel / automatic completion / app background |

Record only UI state and engine/state identifiers. Repair the first invalid transition, and preserve the ability to cancel all in-flight engines.

### F10 — Why the audit is broad despite no wholesale revert

Commit `0a15f7cf` accidentally removed roughly 12,282 lines across `ChatActivity.kt` and `strings.xml`. Commit `7c105200` restored roughly 12,397 lines. Comparing the final branch against `main` shows a smaller intentional net change to ChatActivity rather than the giant deletion remaining in the current tree. A final function inventory did not identify an unexplained missing chat subsystem; the old transcript resize implementation was replaced by `ChatTranscriptRecyclerView`.

This history justifies a surface-by-surface regression pass, but it does not justify reverting days of Phase 8 work.

## Repair and audit sequence

### Slice 1 — Shared serialization safety

**Approved now:** F1 and F2.

- Replace reflection-only wrappers with explicit JSON contracts.
- Preserve/recover only the identifiable `{}` shrinker artifact.
- Add focused storage tests.
- Build a minified beta because this defect is R8-dependent.

### Slice 2 — Drawer and saved-chat actions

**Next after Slice 1 is installed:** F3 and F7.

- Exercise open/close/back against saved and provisional chats.
- Verify no state mutation across drawer transitions.
- Verify the drawer is truly full width.
- Verify Pin, Export Chat, Delete, and Logs appear in the approved order for a saved chat.
- Make only failures demonstrated after storage recovery.

### Slice 3 — Endpoint/model/request truth

**Approved now:** F4.

- Save a changed endpoint model from Quick Settings.
- Verify Quick Settings displays it.
- Verify the next request uses it through both streaming and non-streaming request paths.
- Verify provider routing and reasoning controls resolve against that endpoint/model.
- Confirm unrelated existing chats are not silently rewritten.

### Slice 4 — Companion identity and prompt delivery

**Reproduce first:** F5.

- Test existing chat, newly saved chat, and blank provisional chat.
- Trace only stable IDs and prompt-block presence.
- Fix the earliest proven persistence or refresh break.
- Recheck startup fallback when the selected companion was deleted.

### Slice 5 — Chat presentation and input controls

**Approved now:** F6. **Reproduce/decide first:** F8 and F9.

- Verify token line wrapping at narrow widths, long model names, and portrait overlap.
- Preserve reasoning-token details.
- Inventory profile-image surfaces and resolve the square-thumbnail product conflict.
- Run the transcription engine/state matrix and repair the first invalid transition.

### Slice 6 — Final regression pass

- Launch with valid endpoint, invalid API key, no endpoint, and migrated beta storage.
- Send streaming and non-streaming messages.
- Verify Companion identity/prompt, model/provider, token display, reasoning details, transcription, voice selector, drawer state, New Chat, saved-chat menu, export, and delete choices.
- Run targeted unit/contract checks first; reserve the broad suite for the end.
- Produce a beta artifact from the exact reviewed branch head.

## Areas inspected that should not be changed speculatively

- **Voice selector:** final production files did not show an unexplained change against `main`; test it, but do not rewrite it without a reproduced failure.
- **Companion prompt ordering:** calls are present in both normal request paths; identify the lost ID/state before editing the prompt builder.
- **Delete/export actions:** implementations exist; restore the healthy saved-chat classification before changing their menu logic.
- **Reasoning token details:** keep the new details behavior.
- **Main branch:** no merge or direct modification is part of this plan.

## Owner beta acceptance checklist

- No generic failure toast on ordinary launch.
- Drawer chevrons open/close without changing the underlying chat or composer state.
- Drawer occupies all available width.
- New Chat works from the drawer.
- Saving/returning from character or endpoint screens does not trigger a navigation failure toast.
- Saved-chat overflow contains Pin/Unpin, Export Chat, Delete, and Logs as specified.
- Endpoint model shown in Quick Settings matches the model used by the next request.
- Existing chat retains its selected Companion and the request contains that Companion's prompt block.
- Model/token metadata remains visible and moves tokens to the next line when necessary.
- Reasoning usage remains available in Message Details.
- Transcription visibly enters a stoppable/cancellable state and reliably exits it.
- Profile shapes match the final owner-approved surface rule.
- Voice selection and playback still behave as before.

## Current narrow repair set

The repair accompanying this audit addresses F1, F2, F4, and F6 only: navigation storage recovery, safe deletion-journal persistence/recovery, endpoint-to-chat model synchronization, and token metadata width/wrapping. F3, F5, F8, and F9 remain deliberately gated by post-storage runtime evidence or an explicit product decision.
