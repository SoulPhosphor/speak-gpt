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

The beta had a severe shared storage-contract failure plus several independent regressions or pre-existing defects. Confirmed defects below now have narrow repairs on this branch; device confirmation remains required before calling the beta accepted.

The repeated generic **“Sorry, action failed”** toast is most consistently explained by the new drawer/navigation repository serializing private wrapper fields through Gson reflection in an app where both debug and beta are minified. R8 can remove fields that exist only for reflection. The current ProGuard rules protect the obsolete package `com.teslasoft.assistant.**`, while the application package is `org.teslasoft.assistant.**`; the `SerializedName` rule does not protect these unannotated wrappers. A nominally successful write can therefore become `{}`. The repository subsequently rejects that object as corrupt.

That single unreadable navigation snapshot is consulted during launch, resume, drawer refresh, New Chat flows, and chat-menu construction. It therefore explains why unrelated-looking operations produce the same toast and why **Pin / Export / Delete** disappear from the three-dot chat menu. An invalid API key cannot cause a local storage failure at app launch.

The same reflection pattern exists in the encrypted deletion recovery journal. That is a separate data-safety defect: a delete operation could appear journaled even though only `{}` was stored, leaving recovery unable to identify or finish the interrupted deletion.

Other reports do not all have the same cause. Endpoint/model synchronization is an older two-source-of-truth problem, token metadata was clipped by its layout constraints, and full-width drawer behavior conflicted with `DrawerLayout`'s normal reserved margin. The follow-up pass also found a first-save list rewrite immediately before the durable first commit, reflective decoding in that commit's recovery journal, stale Flower fallbacks plus an uncleared pooled shape-mask bitmap, missing Companion-ID recovery for existing chats, and no explicit stoppable transcription state.

## Findings

| ID | Area | Finding | Confidence | Action class |
|---|---|---|---|---|
| F1 | Launch/drawer/generic toast | Folder catalog may serialize as `{}` under R8, then every navigation snapshot is rejected | High | **Implemented on this branch** |
| F2 | Delete recovery | Deletion journal uses the same unsafe reflection-only wrapper | High | **Implemented on this branch** |
| F3 | Drawer geometry/state | Standard `DrawerLayout` reserves a visible margin; approved spec requires 100% available width and state-preserving chevrons | High | **Implemented; beta confirmation pending** |
| F4 | Endpoint/model | Endpoint editor saves one model while the active chat retains its previous per-chat model (commonly `gpt-4o`) | High | **Implemented on this branch** |
| F5 | Companion identity/prompt | Prompt assembly exists in both request paths; missing/deleted IDs were not recovered for existing chats | High for selection recovery | **Implemented; request/device confirmation pending** |
| F6 | Message token line | Metadata view can measure wider than the bubble because it lacks an end constraint, clipping instead of wrapping | High | **Implemented on this branch** |
| F7 | Chat overflow menu | Menu hides saved-chat actions when navigation snapshot fails; action implementations remain present | High | **Resolved through F1; beta confirmation pending** |
| F8 | Profile image shape | Adapter fallbacks still started as Flower and pooled mask alpha could make Circle render square | High | **Implemented per owner decision** |
| F9 | Transcription | Transcription work showed a progress ring but reset/disabled the mic instead of giving it the Transcribing/Stop state | High | **Implemented; engine matrix pending** |
| F10 | Historical source loss | One intermediate commit gutted ChatActivity/strings, then a later commit restored them; final inventory does not show unexplained wholesale loss | High | No revert; regression test surfaces |
| F11 | First chat save | A pending send rewrote the empty list immediately before first commit; its recovery journal also used reflective DTO decoding | High | **Implemented; beta confirmation pending** |
| F12 | Initial transcript position | Plain `scrollToPosition(last)` could show only the top of a final message taller than the viewport | High | **Implemented; keyboard/device confirmation pending** |

## Detailed evidence and repair boundaries

### F1 — Navigation storage failure causes the repeating toast

Relevant implementation:

- `ChatNavigationRepository` stored `FolderCatalog(version, folders)` using `Gson().toJson(...)`.
- The wrapper was private and its fields were neither accessed directly nor annotated for serialization.
- `app/build.gradle` minifies every build type, debug and beta included.
- `app/proguard-rules.pro` contains legacy `com.teslasoft.assistant.**` keep rules rather than rules for the real `org.teslasoft.assistant.**` namespace.
- The reader manually requires literal `version` and `folders` keys, so `{}` is classified as corrupt.
- `ChatDrawerController.refresh()` surfaces snapshot failure with the generic toast.

Why it appears during many actions:

- ordinary app entry initializes/refreshed drawer state;
- opening the drawer refreshes it;
- returning from another activity can refresh on resume;
- New Chat returns through navigation state;
- the overflow menu asks the navigation snapshot whether the current chat is saved.

Implemented repair (commit `30128274`):

- encode the persisted folder JSON explicitly, without reflection-only wrapper fields;
- recognize only the known empty-object shrinker artifact when the matching schema marker proves it came from the installed schema;
- preserve the original `{}` through the existing corruption-backup path before replacing it with an explicit valid empty catalog;
- keep arbitrary malformed JSON or an unmarked `{}` blocked and untouched.

This is deliberately not a general “erase corrupt storage” policy.

### F2 — What the deletion recovery journal is

Chat/image deletion spans more than one storage operation. The recovery journal is an encrypted, minimal list of stable chat/folder/image identities and the stage reached. If the app is interrupted between deleting metadata and cleaning image files, the next run can safely finish only the recorded work.

The journal used the same reflection-only Gson wrapper as the folder catalog. If it stored `{}`, there are no identities from which any deletion can safely be reconstructed.

Implemented repair (commit `30128274`):

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

Implemented repair:

- use a small `DrawerLayout` specialization that gives the drawer child the exact measured screen width before AndroidX applies its ordinary safety margin;
- retain the existing locked-closed/temporarily-unlocked/locked-open state machine, chevrons, system-Back behavior, and the same live chat root underneath the drawer;
- do not add edge-swipe opening, replace the chevrons, move the composer, or recreate the chat.

### F4 — Endpoint editor and Quick Settings disagree about the model

There are two persisted values:

- an API endpoint profile contains its own model;
- each chat has an active per-chat model used for requests and shown in Quick Settings.

The endpoint editor returns only the endpoint ID. Quick Settings reloaded the endpoint label but did not copy the endpoint's saved model into the active chat. Requests therefore continued using the stale chat value, often the legacy `gpt-4o` default. This behavior also exists on `main`; Phase 8 made it more visible but did not originate every part of it.

Implemented repair for the reported flow (commit `30128274`):

- after an endpoint is saved/selected from a chat, adopt its nonblank model as that chat's active model;
- update the Quick Settings label immediately;
- retain the existing force-refresh path so the request client reloads the persisted values;
- refresh provider-mode and reasoning capability displays against the new endpoint/model pair.

This does not globally rewrite every chat when an endpoint profile is edited elsewhere.

### F5 — Companion shows “No Companion” or appears not to receive its prompt

The normal request builders still assemble the companion/persona prompt before the ordinary system prompt. No direct removal of those prompt calls was found. New provisional chat setup intentionally clears stale identity before seeding the last successful companion or the first available one.

Implemented repair:

- preserve a valid selected Companion;
- if the ID is empty or points to a deleted Companion, recover the last successfully used valid Companion, then the first available Companion;
- apply that recovery to existing chats as well as blank provisional chats;
- leave both existing prompt-assembly paths intact and do not log prompt text or conversation content.

The beta acceptance pass must still confirm that the recovered stable ID reaches request assembly and that the Companion prompt block is present.

### F6 — Model/token metadata does not wrap

`MessageMetadataView` already contains the intended behavior: if model plus token usage cannot fit on one line, it renders tokens on a second line. The assistant layout gave that view `wrap_content` with only a start constraint, allowing it to measure beyond the usable bubble width and be clipped before its wrapping threshold was meaningful.

Implemented repair (commit `30128274`):

- constrain metadata from start to end and use constraint width (`0dp`), giving the custom view the real available width;
- keep reasoning-token reporting in Message Details unchanged;
- do not move reasoning tokens into the top-line token summary, because hidden reasoning can still be inspected through the existing details behavior.

### F7 — Export Chat and Delete disappeared

The action implementations and labels still exist. The overflow menu conditionally includes saved-chat actions only after it can establish that the current chat is present in the navigation snapshot. When F1 makes that snapshot fail, the code falls back to a minimal menu, which is why only Logs may remain.

No duplicate menu implementation was added. The saved-chat identity path and Pin/Unpin, Export Chat, Logs, and Delete action code remain present; F1 restores the healthy snapshot needed for those conditional actions to appear. Re-test their visible order and behavior in the beta.

### F8 — Default profile shape and which images it affects

Owner decision received: Circle is the default, and the one shape control applies to both user and Companion/AI profile portraits.

Implemented repair:

- replace stale Flower adapter/reset defaults with `ProfileImageShape.DEFAULT` (Circle);
- keep both chat avatar refresh paths reading the same global shape and rebinding their respective user and Companion portraits;
- clear pooled transformation bitmaps to transparent before drawing the Circle/Flower mask, preventing stale opaque corners from making Circle look square;
- leave intentionally unmasked non-profile gallery content alone.

### F9 — Transcription control does not enter/leave a stoppable state

The start handlers changed to Listening while recording, but cloud/local transcription reset the microphone and then disabled it while speech-to-text was still active. The input could therefore fail to say Transcribing and the visible mic could not own Stop.

Implemented repair:

- add an explicit transcription-in-progress state;
- immediately show Transcribing with an enabled red Stop mic when cloud or local transcription begins;
- keep a tap routed through the existing all-engine cancellation funnel;
- clear the state on success, empty result, cancellation, failure, and full AI cancellation;
- handle a cloud recorder stop failure without stranding the controls.

Required reproduction matrix:

| Dimension | Values |
|---|---|
| Engine | Google dictation / cloud / local Whisper |
| Permission | already granted / first grant / denied |
| Chat | blank provisional / saved |
| Exit | mic stop / progress cancel / automatic completion / app background |

The matrix remains the beta acceptance test; the repair adds no prompt or transcript logging.

### F11 — First saved chat and interrupted first-commit recovery

The pending-chat send path updated chat-list timestamps before a row for that chat existed. That rewrote the current list immediately before `commitPendingConversation` added the first durable row. The commit itself already creates the correct timestamp, so the preliminary write was unnecessary and risky.

Implemented repair:

- skip pre-commit timestamp mutation for a pending conversation;
- retain timestamp sorting for existing chats;
- decode the pending first-commit journal through explicit JSON fields so R8 cannot strip a private reflective DTO's recovery data;
- prove an existing saved row remains visible after the known `{}` folder-catalog repair.

### F12 — Open at the true end of a chat

The keyboard contract requires a chat already at its end to remain at its end. Initial navigation used only `scrollToPosition(last)`, which guarantees visibility but can position the top of a final row taller than the viewport.

Implemented repair:

- ordinary chat opens use `scrollToTranscriptEnd()`;
- after layout positions the final row, any overflow is corrected so its bottom—not merely its top—is at the viewport end;
- short chats keep normal top alignment and Search-target positioning remains unchanged;
- the existing pre-resize bottom-relative anchor continues to own keyboard/composer size changes.

### F10 — Why the audit is broad despite no wholesale revert

Commit `0a15f7cf` accidentally removed roughly 12,282 lines across `ChatActivity.kt` and `strings.xml`. Commit `7c105200` restored roughly 12,397 lines. Comparing the final branch against `main` shows a smaller intentional net change to ChatActivity rather than the giant deletion remaining in the current tree. A final function inventory did not identify an unexplained missing chat subsystem; the old transcript resize implementation was replaced by `ChatTranscriptRecyclerView`.

This history justifies a surface-by-surface regression pass, but it does not justify reverting days of Phase 8 work.

## Repair and audit sequence

### Slice 1 — Shared serialization safety

**Implemented on this branch:** F1 and F2.

- Replace reflection-only wrappers with explicit JSON contracts.
- Preserve/recover only the identifiable `{}` shrinker artifact.
- Add focused storage tests.
- Build a minified beta because this defect is R8-dependent.

### Slice 2 — Drawer and saved-chat actions

**Implemented; beta confirmation pending:** F3 and F7.

- Exercise open/close/back against saved and provisional chats.
- Verify no state mutation across drawer transitions.
- Verify the drawer is truly full width.
- Verify Pin, Export Chat, Delete, and Logs appear in the approved order for a saved chat.
- Make only failures demonstrated after storage recovery.

### Slice 3 — Endpoint/model/request truth

**Implemented on this branch:** F4.

- Save a changed endpoint model from Quick Settings.
- Verify Quick Settings displays it.
- Verify the next request uses it through both streaming and non-streaming request paths.
- Verify provider routing and reasoning controls resolve against that endpoint/model.
- Confirm unrelated existing chats are not silently rewritten.

### Slice 4 — Companion identity and prompt delivery

**Selection repair implemented; request confirmation pending:** F5.

- Test existing chat, newly saved chat, and blank provisional chat.
- Trace only stable IDs and prompt-block presence.
- Fix the earliest proven persistence or refresh break.
- Recheck startup fallback when the selected companion was deleted.

### Slice 5 — Chat presentation and input controls

**Implemented on this branch:** F6, F8, F9, F11, and F12.

- Verify token line wrapping at narrow widths, long model names, and portrait overlap.
- Preserve reasoning-token details.
- Verify Circle on both user and Companion portraits and verify a shape change rebinds both.
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

## Phase 8.1 repository-wide chat identity audit

The Phase 8.4 exit gate requires the repository-wide audit results to be part of
the branch record, so they are recorded here.

Eighteen production files read the chat list or a chat history. Every one that
needs a chat's stable identity takes it from `ChatPreferences.storedChatId`,
which returns the row's explicit `id` when it has one and the historical title
hash when it does not.

| Production file | Identity reads through the helper |
|---|---|
| `ui/adapters/ChatListAdapter.kt` | 13 |
| `preferences/ChatPreferences.kt` | 8 |
| `preferences/chatnavigation/ChatNavigationRepository.kt` | 6 |
| `conversation/NewConversationCoordinator.kt` | 4 |
| `ui/fragments/tabs/ChatsListFragment.kt` | 4 |
| `preferences/generatedimages/GeneratedImageCatalogBackfill.kt` | 2 |
| `preferences/memory/MemoryExporter.kt` | 2 |
| `imagegen/GeneratedImageFiles.kt` | 1 |
| `preferences/RenameJournal.kt` | 1 |
| `preferences/backup/ChatSnapshotManifest.kt` | 1 |
| `preferences/backup/portable/ChatLogicalSerializer.kt` | 1 |
| `preferences/backup/readable/ReadableChatBackup.kt` | 1 |
| `preferences/memory/TranscriptRecorder.kt` | 1 |
| `preferences/memory/archivist/Archivist.kt` | 1 |
| `ui/activities/ChatActivity.kt` | 1 |

Three of the eighteen read the chat list without needing an identity, and were
checked individually rather than converted: `ui/activities/MainActivity.kt` only
asks whether the list is empty, and `imagegen/ImageGenerationJobRegistry.kt` and
`preferences/chatsearch/ChatSearchIndexManager.kt` are handed a chat ID by their
callers.

The only literal `chat["id"]` read left in production is the helper's own
definition. Two source contracts hold that line: one fails if a new chat-list
reader takes `["id"]` directly, the other fails if the old hash-derived
`addChat` creation path comes back.

The three bugs the review named are fixed. `ChatPreferences.getChatName` finds a
missing-ID row through the helper, `GeneratedImageFiles.deleteIfUnreferenced`
scans that row's history before any file deletion, and `RenameJournal.reconcile`
builds its live-ID set through the helper so a legacy chat is never mistaken for
a deleted one.

## What has actually been executed, and what has not

This distinction decides most of the Phase 8.4 gate, so it is stated plainly.

**Runs on every push.** The JVM unit suite — roughly 2,140 test methods across
291 files — plus the debug and Beta builds, the Beta application-identity
assertion, and compilation of the instrumentation suite. This is the whole of
`Android Checks`, and it is exactly the Gradle gate Phase 8.4 names.

**Compiles but has never run.** The instrumentation suite: roughly 87 test
methods across 10 files, including every generated-image catalog failure
injection and both SQLCipher upgrade fixtures. These need real SQLCipher, and
the app ships `arm64-v8a` native code only, so they cannot run on the x86_64 CI
runner. Nothing in this suite has produced a result.

**Has never been observed at all.** Everything in the device checklist below.
Only the owner can supply those.

The practical consequence: a green `Android Checks` says the source and the
build pipeline are sound. It says nothing about SQLCipher behavior, about the
catalog's real failure handling, or about the Pixel.

## Current repair status

**Phase 8 repair candidate:** `1032d69a`, green on `Android Checks`. Later
commits on this branch add the Phase 8.6 conversion lane and change none of the
repairs audited here.

F1, F2, F4, and F6 are implemented on this branch in commit `30128274`:
navigation storage recovery, safe deletion-journal persistence and recovery,
endpoint-to-chat model synchronization, and token metadata width and wrapping.
F3, F5 selection recovery, F8, F9, F11, and F12 followed. F7 is restored through
F1 rather than through duplicated menu code.

Three further repairs landed after this audit was first written, and are
recorded in `phase-8-repair-table.md` rather than as new findings here: an
absent folder catalog is no longer classified as a storage failure and an
empty-but-present one is now repairable; a keyless-reading endpoint can no
longer clear the chat list of a user who has chats; and a conversation whose
first turn arrives by voice now reaches its first durable commit.

None of this is device-verified. Every repair above has a focused automated
contract, all of which run in CI except the generated-image catalog cases, and
none of which exercise R8, a real database, or the Pixel.

## Remaining runtime and device acceptance work

Nothing on this list may be described as verified until the owner confirms it on
the test device, or until the named runtime actually executes.

**Needs an arm64 Android runtime, not the owner:**

- the SQLCipher 4.16.0-to-4.17.0 upgrade proof for `companion_memory.db`,
  `lorebook.db` and `generated_images.db`, plus encrypted FTS5;
- the generated-image catalog failure injections: absent, empty, locked,
  wrong-key and corrupt catalogs, interrupted registration, and recovery
  idempotency.

**Needs the owner on the Pixel:**

- drawer geometry and state preservation across open and close;
- saved-chat overflow menu contents and order;
- the Companion prompt block actually present in a request;
- the transcription engine, permission, chat and exit matrix;
- Circle rendering for both portrait roles;
- end-of-chat position during real IME animation;
- the folder-metadata repair healing the existing install on next launch;
- new chats surviving a leave-and-return, including voice-first chats;
- the endpoint model reaching Quick Settings and the next request;
- token usage visible and wrapping under a long model name;
- the manual Google dictation turn ending on the second mic tap;
- side-by-side Beta install, launch and uninstall leaving the working
  pre-release and its data intact.
