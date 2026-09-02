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

## Why the previous repair pass had no visible effect

Two reasons, both of which apply to every item below.

**The test suite cannot see this class of failure.** Most of the suite matches
patterns against source text, and none of it runs through R8. The app minifies
every build type, including debug and beta. A failure that only R8 causes leaves
the whole suite green. "2,137 tests passed" was true and told us nothing about
the device.

**The shrinker keep rule protected nothing.** `app/proguard-rules.pro` kept
`com.teslasoft.assistant.**`. This app's classes are in
`org.teslasoft.assistant.**` — the fork's package changed and the rule did not.
The rule matched zero classes, so all 540 of the app's classes were exposed to
shrinking in every build. The earlier audit noticed this and rewrote two
serializers by hand instead of correcting the rule, leaving every other
reflectively-used field in the app exposed.

---

## Repair table

### 1. Repeated "Sorry, action failed" toast, including on ordinary launch

| | |
|---|---|
| **Reported symptom** | The generic failure toast appears on ordinary launch and after unrelated actions. |
| **Working main behavior** | `main` has no drawer and no navigation snapshot, so no such toast exists. |
| **Branch regression** | `ChatDrawerController.refresh()` raises the toast whenever the navigation snapshot fails. It runs in the controller's constructor (every chat screen creation) and again in `ChatActivity.onResume` (every return from Settings, an endpoint editor, a character screen). One unreadable navigation store therefore produced the toast on launch and after any unrelated screen. The snapshot also fails as a whole when only the *folder* catalog is unreadable. |
| **Exact fix** | `refresh(userInitiated)` separates the automatic refreshes (construction, resume) from the ones the user asked for (opening the drawer, completing a folder/pin/move/delete action). An automatic refresh keeps whatever the drawer already shows and stays silent; a user-initiated one still reports failure. Correcting the shrinker keep rule removes the underlying cause of the snapshot failures. |
| **Automated verification** | `ShrinkerKeepRuleTest` (new) fails if the keep rule stops matching the app's real package. |
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

### 4–13

Under investigation. Rows are added here as each is traced; none is marked
repaired before its runtime path is established.

