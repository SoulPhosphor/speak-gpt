# Phase 8 Beta — Live Repair Table

**Branch:** `agent/phase-8-pre-main-safety`
**Baseline for comparison:** `main` (`0d16b3c3`)
**Beta that failed on device:** branch head `de301bc1`

This table is the live record for the reported beta failures. It is updated as
each item is investigated and repaired. Nothing here is called fixed on a device
until the owner confirms it on the test device.

## How to read the verification columns

- **Code-verified** — the runtime path was traced in the current source and the
  change is covered by a check that runs in CI.
- **Device-verified** — the owner exercised it on the Pixel 8 and confirmed the
  symptom is gone. Only the owner can fill this in.

## Correction to an earlier claim in this file

An earlier version of this document said the shrinker keep rule explained the
beta failures. That was checked against the actual binaries and it is **wrong**.
The working `latest` build from `main` is missing 253 of its own 735 classes
from its DEX and works fine — R8 removing app classes is normal here. The keep
rule was genuinely aimed at the wrong package and is still worth fixing, but it
is not the cause of what you saw, and it is not the headline finding.

The real shared cause of the "everything fails" cluster is item 1 below.

## Why the previous repair pass had no visible effect

Two reasons, both of which apply to every item below.

**The test suite cannot see this class of failure.** Most of the suite matches
patterns against source text, and none of it runs through R8. The app minifies
every build type, including debug and beta. A failure that only R8 causes leaves
the whole suite green. "2,137 tests passed" was true and told us nothing about
the device.

**The shrinker keep rule was aimed at the wrong package.** `app/proguard-rules.pro`
kept `com.teslasoft.assistant.**`; this app's classes are `org.teslasoft.assistant.**`.
The rule matched zero classes. Corrected, with a test — but see the correction
above: this is a latent risk that was removed, not the cause of the failures.

---

## Repair table

### 1. Repeated "Sorry, action failed" toast, including on ordinary launch

| | |
|---|---|
| **Reported symptom** | The generic failure toast appears on ordinary launch and after unrelated actions. |
| **Working main behavior** | `main` has no drawer and no navigation snapshot, so no such toast exists. |
| **Branch regression** | `ChatNavigationRepository.migrateSchema()` treats an absent folder catalog as normal — it is the ordinary state of a device that never made a folder. But `snapshot()` and every mutation classified the folder read through a catch-all `else` that reported "storage unavailable". So a device with no stored catalog failed **the entire drawer read**, the saved-chat lookup that Pin / Export Chat / Delete hang off, and pinning itself. `ChatDrawerController.refresh()` raises the generic toast on that failure, and it runs when a chat screen is created and again on every resume — which is why it fired on launch and after returning from any other screen. One misclassification, the whole cluster. |
| **Exact fix** | Every folder-read outcome is now classified explicitly. Absent means no folders. Only genuine corruption, an unsupported schema, or a store that cannot be opened remain failures — those still surface, because hiding them would be wrong. |
| **Automated verification** | `ChatNavigationStorageHealthTest.anAbsentFolderCatalogListsChatsAndStillAllowsPinning` (new) — the drawer lists chats and pinning succeeds with no catalog, and nothing is treated as corruption. `ShrinkerKeepRuleTest` (new) guards the keep rule separately. |
| **Reverted** | The earlier attempt to silence the toast on automatic refreshes. That hid the fault instead of fixing it; `ChatDrawerController` is byte-identical to before. |
| **Device verification** | Pending. |

### 2. Chats created in the app disappear and never reach the drawer

| | |
|---|---|
| **Reported symptom** | A chat created in the app is gone after leaving the app, and never appears in the drawer. |
| **Working main behavior** | `ChatPreferences.addChat` wrote the chat's row into `chat_list` at the moment the chat was created. The chat existed from then on. |
| **Branch regression** | Phase 8 deleted `addChat` and made a new conversation *provisional*: it owns a UUID, its settings and its history, but no chat-list row until a "first commit" runs. Three defects then made that commit unreachable or destructive.<br>**(a)** Only the typed-send path commits. Cloud transcription (`processRecording`) and on-device Whisper (`processLocalWhisperTranscript`) record their turn through `putMessage` and never commit — so any conversation whose first turn arrives by voice stays provisional forever while its turns pile up on disk.<br>**(b)** `ChatActivity.onDestroy` calls `abandonPendingConversation` for any still-provisional chat, which **cleared its history and settings outright** — with no check that the conversation was empty. Leaving such a chat destroyed it.<br>**(c)** The startup blank chat and a drawer New Chat are both handed `_autoname_1`, because the placeholder number was taken from the chat list and neither has a row yet. Whichever committed second was rejected as a duplicate title and then discarded by (b). |
| **Exact fix** | (a) `putMessage` funnels into `commitPendingConversationIfNeeded()`, so every path that records a turn — voice, hands-free, image, and any future one — commits the same way the typed path does.<br>(b) `abandonPendingConversation` now discards **only** a conversation it can read and that is provably empty. One holding turns has its first commit finished instead; an unreadable store is left untouched.<br>(c) Placeholder titles are reserved across live provisional conversations, and a placeholder collision at commit time is renumbered instead of refusing the commit. A title the user chose still may not be duplicated.<br>Plus recovery: `recoverPendingCommits()` now also adopts and commits conversations that hold turns but never reached the commit journal, including ones orphaned by the builds that had this defect. |
| **Automated verification** | `PendingConversationDurabilityTest` (new). |
| **Device verification** | Pending. |

### 3. Saved-chat three-dot menu is missing Export Chat and Delete

| | |
|---|---|
| **Reported symptom** | A saved chat's overflow menu shows only Logs. |
| **Working main behavior** | The menu's actions did not depend on folder metadata. |
| **Branch regression** | Two causes. The menu decides a chat is "saved" by looking it up in the whole navigation snapshot, which fails as a unit when the *folder* catalog is unreadable — so a folder problem hid Pin, Export Chat and Delete on a perfectly healthy chat. And with item 2 unfixed, a chat that never got its list row genuinely was not saved, so the menu was correct and the chat was the problem. |
| **Exact fix** | `readSavedChatRow()` answers "is this chat saved, and is it pinned" from the chat list alone. Folder organization no longer gates export or delete. Item 2's fix supplies the missing rows. |
| **Automated verification** | `ChatDeletionCallSiteContractTest` updated to assert the chat-list identity and to fail if the snapshot lookup returns. |
| **Device verification** | Pending. |

### 2b. A keyless-looking endpoint could erase the whole chat list at launch

| | |
|---|---|
| **Reported symptom** | Part of "chats disappear". Found while tracing item 2; it also exists on `main`. |
| **Defect** | The launch path that sends a brand-new user to Welcome wrote an empty chat list unconditionally first. It runs whenever the configured endpoint reads as having no key — which is not the same thing as "this user has no chats". Any condition that makes the active endpoint resolve keyless therefore erased every conversation on the device. |
| **Exact fix** | The list is now cleared only when it is readable **and** already empty. An unreadable list is never overwritten. |
| **Automated verification** | Compile/CI only; the surrounding startup path has no unit harness. |
| **Device verification** | Pending. |

### 4. Endpoint model changes do not reach Quick Settings or the request

| | |
|---|---|
| **Reported symptom** | Changing the endpoint's model does not change what Quick Settings shows or what the request uses; the older `gpt-4o` behavior takes over. |
| **Working main behavior** | Same two-source-of-truth existed on `main`; Phase 8 made it more visible. |
| **Branch regression / defect** | The chat screen caches the model in a field and builds every request from that cached copy. Quick Settings writes the new model straight to storage and then tells the screen to update — but the update handler only refreshed avatars, so the live conversation kept sending the model it opened with. The full-rebuild path (`onForceUpdate`) does re-read storage, but it finishes the screen and starts a fresh copy without carrying the provisional flag — so making a Quick Settings change in a brand-new chat ran the abandon path against the very conversation the replacement screen was opening. |
| **Exact fix** | The Quick Settings update handler now re-reads the chat's model, prefix and end separator. The rebuild path carries the provisional state into the replacement screen and marks the finish as a self-replacement, so it can never abandon the conversation it is handing over. |
| **Automated verification** | Covered by the existing Quick Settings endpoint-sync contract test; the model reload is asserted by the new source contract in `PendingConversationDurabilityTest`'s sibling checks. |
| **Device verification** | Pending. |

### 5–6. Companion shows "No Companion"; Companion prompt delivery

| | |
|---|---|
| **Status** | Repair already on the branch (`ensureActiveCompanion`), not yet confirmed on the device. |
| **What is established in code** | Selection recovery runs on every chat open, for existing chats as well as new ones, and both request builders still assemble the Companion prompt block. The Companion label reads "No Companion" only when the chat's stored Companion id is empty or names a deleted Companion. |
| **What is not established** | Why the id would be empty on the owner's existing chats after that recovery runs. The remaining candidate is the shrinker (item 1's keep rule), which the new beta changes. |
| **Next step** | Re-test on the new beta before changing the selection code again. |

### 7–8. Portrait shape defaults to Circle; one setting drives both portraits

| | |
|---|---|
| **Status** | Repair already on the branch, not yet confirmed on the device. |
| **What is established in code** | `ProfileImageShape.DEFAULT` is Circle, `GlobalPreferences.getProfileImageShape()` defaults to `"circle"`, and no remaining fallback starts at Flower. Both the user and Companion portrait paths read the same global shape. The pooled mask bitmap is cleared to transparent before drawing, so a recycled opaque bitmap can no longer make Circle render square. |
| **Next step** | Re-test on the new beta. |

### 9. Drawer open/close must preserve live chat state

| | |
|---|---|
| **What is established in code** | The drawer wraps the *existing* chat view rather than recreating it, so composer text, IME state, attachments and streaming survive open/close. Back closes the drawer first. Full width is applied by the drawer's own measure pass. |
| **Change made here** | Opening the drawer no longer risks losing a provisional conversation, because tapping a chat in the drawer finishes the current screen and that finish now commits rather than discards (item 2). |
| **Next step** | Re-test the spec's state-preservation checklist on the new beta. |

### 10. Token usage missing / does not wrap under long model names

| | |
|---|---|
| **What is established in code** | The metadata view is constrained start-to-end at `0dp`, so it now measures against the real bubble width, and it drops the token count onto its own line when both do not fit. Reasoning-token detail in Message Details is untouched. The line is hidden when Model Names / Token Usage are off or the turn stored no count. |
| **Not yet explained** | "Token usage is missing" as distinct from "does not wrap". If it is still missing on the new beta, the next thing to check is whether the turn stored a token count at all. |
| **Next step** | Re-test on the new beta. |

### 11. Google dictation cuts you off mid-sentence

**Not a Phase 8 regression.** The owner confirmed it behaves the same on the
working build, and the branch's transcription code is byte-identical to `main`.
This is a pre-existing defect in the Google dictation path.

| | |
|---|---|
| **Reported symptom** | Pressing the mic opens it correctly, then the recogniser stops the user after a word, or after two sentences — inconsistently. It never waits the way Whisper does. |
| **Why Whisper feels right** | Cloud Whisper single-turn is press-to-start / press-to-stop. The user ends the turn, so nothing can cut them off. Google dictation hands endpointing to the OS recogniser, which ends the turn on its own after roughly two seconds of silence. |
| **Actual defect** | Voice & Speech has a **Hands Free Timers** section with **Silence Wait (Seconds)** (default 5) and **Wait for Speech (Seconds)** (default 10). Both are honoured **only while hands-free mode is on**. A single mic press applies neither.<br><br>In hands-free mode `startRecognition` sets the three `EXTRA_SPEECH_INPUT_*` silence hints, `onEndOfSpeech` deliberately does not end the turn, `onError` treats `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` as harmless and restarts the recogniser, and `onResults` buffers each fragment, restarts the mic, and submits only after `scheduleHandsFreeSubmit()`'s configured silence window — because, as the code's own comment says, the native recogniser ignores those extras on most devices.<br><br>A single mic press gets **none** of that: no extras on the intent, `onEndOfSpeech` immediately sets `isRecording = false` and idles the mic, and `onResults` submits whatever fragment the OS returned. The turn therefore ends at the OS recogniser's first pause — one word if the user paused to think, two sentences if they did not. |
| **Machinery that already exists** | `scheduleHandsFreeSubmit()` already reads the configured window rather than hardcoding it, and already buffers and restarts. The fix is to drive a single mic turn through the same mechanism, not to invent timing. |
| **Constraint on the fix** | A manual mic turn must keep obeying `shouldAutoSendTranscription`: the assembled text goes into the message box (auto-sent only when Auto-send is on and the box was empty). It must not start behaving like a hands-free turn. |
| **Blocked on** | One owner decision — which settings govern a single mic press. The two existing fields are labelled *Hands Free* Timers, so reusing them changes what an approved label covers. |
| **Automated verification** | Not written yet; blocked on the decision above. |
| **Device verification** | Pending. |

### 12. Opening a chat at the end does not stay at the end when the keyboard appears

| | |
|---|---|
| **What is established in code** | Opening uses an end-of-transcript scroll that corrects a final row taller than the viewport, and the keyboard resize is held by a bottom-relative anchor captured before the height changes. |
| **Next step** | Re-test with real IME animation on the new beta. |

### 13. Chat surfaces and menus that were not supposed to change

| | |
|---|---|
| **Finding** | Nothing was silently lost. Comparing the branch against `main`: **no user-facing string was removed** (0 of `main`'s string names are missing), and only two functions are gone from the chat screen — `deleteCurrentChat`, replaced by the shared deletion coordinator, and `restoreTranscriptAnchorAfterResize`, replaced by the transcript view that owns the keyboard anchor. The only removed layout is `activity_main.xml`, the old tabbed chat-list screen the approved drawer specification replaces. |
| **Conclusion** | The earlier accidental 12,000-line deletion was genuinely restored. If a specific surface still looks wrong on the device, it is a live wiring problem, not lost source — name the screen and it gets traced individually. |

