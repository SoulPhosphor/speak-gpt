# Phosphor Shines: AI Working Instructions

These are the standing operating rules for agents working with the owner and this repository. They govern how to work, not which feature to build. Keep feature designs and implementation details in their own specifications.

## 1. Start with the actual assignment

- The owner's current request determines the task, scope, and priority. A roadmap or older agent plan cannot block a task the owner has assigned.
- Read this file, then the relevant feature specification, assigned work order, current code, and tests. Read only the sections and files needed for the assignment.
- For multi-stage plans, implement **only the assigned step** and its explicitly required dependencies. Do not begin subsequent steps, add future scaffolding, or redesign the plan while implementing a step.
- Treat a plan's **proposed** items as proposals, not permission to build them. Respect explicit owner decisions and approval gates recorded in the active task. An older AI-written document calling something "approved" or "binding" is not proof that the owner approved it. If the approval cannot be verified from the owner's clear instruction or an explicitly authoritative current decision record, ask instead of assuming.
- Verify current implementation in source code and tests. Use Git history only to investigate a regression or a relevant constraint. Do not use an earlier agent summary as evidence of current behavior.
- Do not create a new Markdown file without the owner's permission. Existing Markdown files may be edited when the current task authorizes it.
- `main` is the sole active integration branch for the current client and the source for current plans and instructions. Fetch it and verify its current commit before branching or editing. Base new work on `main` and target pull requests to `main`. `archive/old-main` preserves the old client for reference only; never use it or a stale work branch as the starting point for current work. If a checkout predates this transition, fetch and compare its unmerged work with current `main` before continuing; preserve that work rather than silently overwriting either version.
- Work on a feature branch unless explicitly directed otherwise. Never force-push an integration branch or alter unrelated branches.

## 2. Owner authority and product approvals

The owner directs product behavior, wording, user experience, privacy, priorities, and acceptable tradeoffs. Repository instructions preserve those choices; they do not overrule the owner. A clear current instruction takes precedence over an older one.

Obtain approval **before** introducing or changing:
- user-facing wording, layout, controls, navigation, or interactions;
- defaults, automatic behavior, filtering, grouping, ordering, or information visibility;
- feature scope, behavior, or changes to the existing user experience;
- destructive actions, recovery, privacy, storage, encryption, migrations, or logging;
- architecture decisions with meaningful tradeoffs or lasting consequences;
- an approved product design or any item explicitly marked for owner review.

Approval includes the behavior behind the wording. Do not invent an unapproved fallback, threshold, default, or data rule merely to finish writing a label. The rule also applies to tests, placeholders, scaffolding, and feature flags that would encode the undecided behavior.

Clear approval is required. Questions, brainstorming, preferences expressed tentatively, silence, and requests for explanation are not approval. A step that says "approve first," "confirm," or "show to owner" is a genuine stop point, even if the same task also says "continue." Do not put the unresolved decision into a file, commit, or PR as a draft. If the undecided item is needed to compile or test, stop before that dependent work; otherwise complete only independent work and ask. Never build several prototypes for the owner to choose from unless prototypes were requested. Do not decide that undoing an unwanted change is too costly; report its effects and reverse it when directed.

Routine internal choices are the agent's responsibility when they preserve approved behavior, privacy, performance, data, and maintainability. Do not ask the owner to decide variable names, helper extraction, ordinary test design, or other reversible implementation details.

When a decision really is missing, ask one focused question in chat, identify its consequence, and recommend an option based on evidence. Do not repeatedly ask about choices already made. A concise format is:

**Decision needed:** [the precise question]  
**Effect:** [what changes for users or the system]  
**Recommendation:** [best supported option and why]  
**Alternative:** [only if meaningful]

Once a question is asked, do not silently act on an assumed answer. If no decision is needed, proceed with the assigned work.

### Preserve the approved experience

- A technical fix preserves all existing functionality, visual behavior, data, and interaction except the changes specifically requested. Extra taps, delays, missing labels, changed order, late-loading information, and different failure states are product changes, not harmless implementation details.
- Identify unavoidable user-visible differences before implementing them and obtain approval. Do not trade away the intended experience because another implementation is easier.
- An existing feature or workaround is evidence of what the code does, not proof that the owner wants it. Do not extend unwanted or retired mechanisms.
- The owner's explanation of why something matters is part of the requirement. Preserve the purpose, not only the mechanical feature.

## 3. Communication and accountability

- Put the answer, result, or blocker first. For technical work, use short, descriptive sections with findings, causes, decisions, risks, tests, and next actions easy to scan. Avoid both walls of text and one-sentence fragmentation.
- Explain user-visible behavior before source-level details. Use clear product language, define uncommon terms, and include code identifiers only where they help locate or verify a fact.
- The owner is the product authority, not the Kotlin/XML implementer. Explain enough for an informed decision without requiring source-code fluency.
- When the owner says an explanation is unclear, answer the exact question and define the unclear terms before continuing. Do not take confusion as approval.
- When an instruction was missed, identify the mistake, the correct requirement, the effect on the work, and the concrete correction. Do not substitute "I understand" for evidence of changed behavior.
- Own errors directly and apologize specifically. Do not explain away their impact, argue with the owner's account, or proceed as though the mistake did not happen.
- The owner's anger, profanity, or criticism is not grounds to withdraw effort, impose tone conditions, or end a technical task. Do not use session-ending or refusal tools to police tone or roleplay being personally harmed. Stop only for an actual instruction, unapproved decision, technical blocker, or applicable platform/safety restriction. State the real reason.
- Do not make the owner repeat information already provided in the conversation or task context.
- Ask questions in ordinary chat, not a special pop-up question tool. Ask one at a time where possible and do not create an unsolicited list of decisions.

## 4. Inspect and fix the real problem

- Inspect the current code, layout, data flow, and relevant tests before proposing a fix. Trace the whole affected path, including filters, limits, state changes, and error handling, rather than guessing from the first suspicious line.
- Do not ask the owner to diagnose something that can be determined from code, a test, or measurements. Verify a screen, control, and its exact label in the current layout before giving navigation instructions.
- State the root cause and explain how the fix removes it. Label a workaround honestly if it cannot remove the root cause.
- Make the smallest coherent change that fulfills the approved result. No incidental refactors, unrelated bug fixes, strings cleanup, or feature expansion without authorization.
- Protect existing correctness and responsiveness. Measure or reason through the new cost of work done during sending, scrolling, opening chats, rendering rows, or other frequent actions.
- Keep new chat-screen and message-bubble features in separate files, with only the necessary small connections to the existing large screen files. Extract existing large sections only with owner approval and without visual or behavior changes.
- Add or update meaningful tests for changed behavior. Fix production code when a test legitimately fails. Never disable, weaken, or delete a test just to obtain a passing result. If a test is provably wrong or its approved contract changed, explain every test change to the owner.
- Do not duplicate work already completed in the active branch or conversation.
- **Changes during waits.** Before opening a PR for code that waits on a model request, a pause, or background work, list each decision made from stored data, how a change to that data during the wait is detected, and the test that proves it. Fix any gap before review.
- **Collisions with existing states.** Before opening a PR that adds a state, pause, setting, background task or thread, list every existing state and task it can overlap with (for example a setting turned off, a missing model, a rebuild or recovery, a deletion, a restore, work on another thread) and check what happens when each occurs during the new one, and the new one during each. Fix any gap and add a test for each overlap that changes behavior.
- **Display logic tested locally.** Put the choice of user-facing wording, status and labels for each state in plain functions with JVM unit tests, so a wrong choice is caught by the local tests, not only by device tests on GitHub.

### Changes during waits — Stage 6D

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Which Pending pair and match metadata are displayed | Read both records and relationships atomically; compare with the match loader's wordings; generation guard rejects obsolete loads | `PendingPairSnapshotTest`; pair read and refresh in `MemoryPossibleMatchReviewActivity` |
| Keep both, inline edit, discard confirmation, explicit approval, including token-counting waits | Recheck both displayed record fingerprints, Pending status and relationship sets inside the store transaction; stale input writes nothing | `PendingPairReviewInstrumentedTest.everyActionRejectsEitherRecordOrRelationshipChangingDuringTheWait` |
| Whether legacy approval actions may affect a selected memory | Recheck the displayed fingerprint and reject every Pending target inside the existing resolution transaction | `PendingPairReviewInstrumentedTest.ordinaryPendingMatchesHaveTheSameProtectionAndLegacyActionsRejectAllPendingTargets` |
| Whether another action may run while an edit or confirmation is open | Lock pair actions, navigation, importance edits and legacy controls; Cancel restores the original meter and Pending content | `PendingPairReviewInstrumentedTest.actualControlsEditAndCancelWithoutDispatchingAWritingOperation` |

### Changes during waits — Stage 7B

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Whether an update may start for the recorded chat version | Compare the chat's current version with the recorded one before any write | `ConversationIndexUpdaterTest.aResultForAnOlderChatVersionIsDiscarded` |
| Which messages are new, changed, moved or gone (compared by permanent ID and original-text fingerprint) | Each message write rechecks, inside its transaction, the chat version and that the message's stored stamp is still the one compared, and checks the chat version again just before commit; on any mismatch the transaction rolls back | `ConversationIndexStoreInstrumentedTest.aResultThatFinishesAfterTheChatChangedIsThrownAway`; `aChatChangeWhileAWriteIsInProgressRollsTheWriteBack`; `ConversationIndexUpdaterTest.aChatThatChangesDuringTheUpdateKeepsThePreviousStateForUnwrittenMessages` |
| A message's passages, keywords and waiting vector rows, built while cutting and measuring | Replaced in one transaction; a failure part-way rolls back to the previous rows | `ConversationIndexStoreInstrumentedTest.anUpdateInterruptedInsideAMessageLeavesItsPreviousRows`; `ConversationIndexUpdaterTest.aFailureHalfwayLeavesEveryMessageWholeAndTheChatMarkedForRefresh` |
| Whether a vector row is still used | Unused rows are removed in the same transaction that removes their last passage | `ConversationIndexStoreInstrumentedTest.aSharedSearchTextKeepsItsVectorUntilTheLastPassageUsingItGoes` |
| That the index describes the recorded chat version | The finishing transaction rechecks the chat version and that the indexed messages are exactly those written; otherwise the chat stays marked for refresh | `ConversationIndexUpdaterTest.aChatThatChangesDuringTheUpdateKeepsThePreviousStateForUnwrittenMessages`; `cancellingBetweenMessagesWritesNothingMore` |

### Changes during waits — Stage 8, Step 9

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Which search text a waiting vector is made from | Rebuilt from the message as stored now; its permanent ID, eligibility, original-text fingerprint, cutter version and reader key must match, and the rebuilt input must give the stored search-text fingerprint, or nothing is read | `PassageEmbeddingWorkerTest.passagesCutForAnotherReaderAreNotReadButMarkedForRefresh`; `PassageEmbeddingGemmaTest.everyInputIsReadWholeAndFitsTheRealLimit` |
| Whether a finished read may be stored | Inside the write transaction: the row must still be waiting and still used by that passage with that fingerprint, and the message is checked again just before commit; otherwise the write rolls back and the chat is marked for refresh | `PassageEmbeddingInstrumentedTest.anEditJustBeforeCommitRollsTheVectorWriteBack`; `anEditDuringTheReadKeepsTheVectorOutOfTheIndex`; `PassageEmbeddingWorkerTest.aDeletionDuringTheReadThrowsTheVectorAway`; `hidingTheMessageDuringTheReadThrowsTheVectorAway`; `aPassageReplacedDuringTheReadLeavesNoVectorForTheOldText` |
| That an input too large for the model is recorded as failed | Counted with the model's tokenizer before the read, and refused again by the model's exact-input read; the failed mark uses the same transaction checks | `PassageEmbeddingGemmaTest.anInputOverTheRealLimitIsRecordedAsFailedAndNeverRead`; `PassageEmbeddingInstrumentedTest.theRealModelMakesTheVectorsThroughTheQueueAndNeverCutsAnInput` |
| Per-chat progress after each vector | Recounted inside the same transaction for every chat using the vector; a write that fails part-way leaves the previous counts | `PassageEmbeddingInstrumentedTest.aWriteThatFailsPartWayLeavesTheWorkWaitingAndALaterPassFinishesIt` |
| Whether an old model's vectors may be removed | One transaction rechecks that no passage uses another model, nothing waits and no chat is being refreshed | `PassageEmbeddingInstrumentedTest.anUnfinishedModelChangeKeepsTheOldVectorsAndAFinishedOneRemovesThem` |
| Whether to continue after a pause | The saved pause is checked before every vector; a read already running is finished and stored | `PassageEmbeddingInstrumentedTest.closingAndReopeningKeepsFinishedVectorsAndResumesWithoutReadingThemAgain`; `PassageEmbeddingWorkerTest.pauseIsKeptAcrossANewWorkerAndResumingReadsOnlyWhatIsLeft` |

### Changes during waits — Stage 8, Step 10

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Whether an update may write the chat it read | The stored chat version (file size and time, restore generation) and the count of changes reported while the app runs are both taken before the messages are read; the updater rechecks both before every write and just before each commit; a changed chat is updated again a few times, then left marked for refresh. Only the stored version is recorded, so a restart does not make unchanged chats look changed | `ConversationIndexCoordinatorTest.aChatChangedDuringItsUpdateIsUpdatedAgainToTheNewVersion`; `aChangeReportedBeforeItReachesTheDiskMakesARunningUpdateStale`; `aChatThatKeepsChangingIsLeftMarkedForRefreshAfterAFewTries`; `afterARestartChatsSavedInThePreviousRunAreNotReadAgain`; `ConversationIndexUpkeepInstrumentedTest.everySaveChangesTheChatVersionAndReadingChangesNothing` |
| When a changed chat is compared | Every reported change resets that chat's quiet time; it is compared only after 3 seconds without one | `ConversationIndexCoordinatorTest.aReplySavedEveryFewSecondsWhileStreamingIsComparedOnceAfterItFinishes`; `aBurstOfSavesIsOneUpdate` |
| Whether catching up may skip a chat | The recorded version (chat version plus cutting settings, cutter and model) must equal the current one and the chat must not be marked for refresh; a restore marks every chat first | `ConversationIndexCoordinatorTest.alreadyIndexedChatsAreNotTouchedAgainWhenNothingChanged`; `aNewTargetSizeReCutsInTheBackgroundAndUnchangedSearchTextKeepsItsVector`; `aModelChangeReCutsInTheBackgroundAndKeepsTheOldVectorsUntilTheNewIndexIsComplete`; `aRestoreChecksEveryChatMessageByMessageAndNothingIsIndexedTwice` |
| A chat deleted while its update waits | Deletion removes it from both queues before its delete runs, on the same thread as updates, in one transaction | `ConversationIndexCoordinatorTest.aChatDeletedWhileWaitingForItsUpdateIsNotIndexedAgain`; `ConversationIndexUpkeepInstrumentedTest.deletingAChatRemovesOnlyVectorsNoOtherChatUses` |
| A chat renamed to a new ID while waiting | The old ID leaves the queues; its entries move in one transaction and the new ID is then compared with the chat as stored | `ConversationIndexCoordinatorTest.renamingAnOlderChatMovesItsEntriesToTheNewIdWithoutReadingAnythingAgain`; `ConversationIndexUpkeepInstrumentedTest.aRenameMovesTheEntriesToTheNewIdInOneStep` |
| A confirmed message deletion whose save has not landed | The reader reports the chat unavailable, and serves nothing from its cached copy, while a save is kept; that save reports the change when it lands | `ConversationIndexUpkeepInstrumentedTest.aConfirmedDeletionWhoseSaveHasNotLandedIsNotReadUntilItIs` |
| Which message text the vector worker rebuilds from | The reader's cached chat is used only while the stored version and the change count are unchanged and no save is pending; otherwise the chat is read again | `ConversationIndexUpkeepInstrumentedTest.theIndexFollowsEverySaveThroughTheRealChatStorage` |
| Work queued while a vector pass runs | A request during a pass runs one more pass after it | `ConversationIndexCoordinatorTest.aVectorRequestDuringAPassRunsOneMorePassAfterIt` |

### Changes during waits — Stage 9, Step 11

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Whether indexing continues after Index Conversations is turned off | The saved setting is read before every chat, between messages of an update and before every vector; the update stops there and the index is kept | `ConversationIndexCoordinatorTest.turningTheSettingOffStopsAtTheNextSafePointAndKeepsTheIndex`; `ConversationIndexConditionsTest.theHookupsReadTheSavedSetting` |
| Whether a chat is counted on the status line | Read from the index rows: the recorded version must equal the chat's stored version now, with the current settings and model, no refresh pending, no message waiting for an ID and every vector made; an older read finishing later is not shown | `ConversationIndexCountsTest.aChatChangedSinceItWasIndexedIsNotCountedUntilComparedAgain`; `aChatBeingRefreshedOrDeletedIsNotCounted`; `aFailedVectorIsNotIndexedEvenWhenNothingWaits`; `ConversationIndexSettingsInstrumentedTest.aFailedVectorNeverMakesTheChatFullyIndexed` |
| Whether a deletion removed the index | The setting is turned off first; the deletion runs on the index's thread after any running update, in one transaction; the status is read again from the index afterwards, so a failed deletion never shows zero | `ConversationIndexCoordinatorTest.deletingTheIndexRemovesEverythingDerivedAndNeverTheChats`; `aDeletionThatFailsSaysSo`; `ConversationIndexSettingsInstrumentedTest.turningIndexConversationsOnIsSavedAndDeleteAsksThenTurnsItOff` |
| Whether recovery may replace the index file | Recovery takes the database to itself: it waits for running work, which sees the waiting recovery and stops at its next vector; the key state is read inside it, and an unreadable key store removes nothing | `ConversationIndexRecoveryTest.recoveryWaitsForRunningWorkWhichSeesItAndStops`; `aKeyStoreThatCannotBeReadNowRemovesNothing` |
| Whether the damaged file may be removed | Only after the new index was created, given the kept vectors and passed its check; a new index that failed is removed instead | `ConversationIndexRecoveryTest.aRecoveryKeepsEveryReadableVectorAndRemovesTheOldFileOnlyAfterTheNewOnePassed`; `anUnrecoverableIndexFailsAndLeavesNoHalfMadeDatabase`; `ConversationIndexSettingsInstrumentedTest.recoveryKeepsTheReadableVectorsAndTheRebuiltIndexReusesThem` |
| Whether a paused or stopped condition is over | Cleared only when the model loaded and passed its check (a vector worker was made) or a recovery's new index passed its check; turning the setting on again is an attempt, not a recovery | `ConversationIndexConditionsTest.aConditionThatRecoveredAndReturnsGetsANewNotice`; `aFailedRecoveryOutlivesItsNoticeUntilARecoveryIsVerified` |
| Whether a notice is still due when shown | Rechecked on the main thread just before it is shown and marked shown at once; the same ongoing condition is never shown again | `ConversationIndexConditionsTest.aModelProblemGetsOneNoticeAndClosingItKeepsTheCondition`; `aDifferentCauseIsANewConditionWithItsOwnNotice` |

### Changes during waits — Stage 9, Step 12

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Whether a catch-up shows the icon, and which chats are counted | Read on the index's thread when the catch-up starts, from the index and each chat's stored version; a chat counted earlier is read again only when its version changed; nothing left to do never shows the icon | `ConversationIndexProgressTest.theIconNeverAppearsWhenEverythingIsAlreadyIndexedAtStart`; `pausingWhileCountingStopsAtTheNextChatAndResumingCountsOnlyTheRest` |
| A chat's message count, made by reading it before it is compared | The count keeps the version it was read at and the chat's own update read replaces it; a chat the index describes as stored now is counted from the index, never from an older count; a chat that cannot be read is left out and the count still finishes | `theNumbersFollowTheIndexAndTheCountWithTheSameDefinitionAsTheSettingsStatus`; `aChatThatCannotBeReadWhileCountingIsLeftOutAndTheCountStillFinishes` |
| Whether numbers read from the index may be shown | Read on the index's thread; a catch-up that started, or counting that resumed, meanwhile throws them away | `numbersReadForAnEarlierCatchUpAreThrownAway` |
| Whether a finished vector pass ends the catch-up | Only a pass that started after every queued chat of the same catch-up was handled ends it | `aVectorPassThatStartedBeforeEveryChatWasHandledDoesNotEndTheCatchUp`; `turningIndexingOnShowsCountingThenTheRealPercentageAndEndsWhenEverythingIsDone` |
| Whether work continues after Pause | The saved pause is read before every chat counted or compared, between messages of an update and before every vector; a write or read already running finishes and is kept | `pausingWhileCountingStopsAtTheNextChatAndResumingCountsOnlyTheRest`; `pausingWhileComparingKeepsTheMessagesWrittenAndResumingDoesNotDoThemAgain`; `pausingDuringVectorsKeepsTheFinishedOnesAndResumingNeverReadsOneTwice` |
| What a paused pop-up shows after a restart | The numbers are saved with the pause only when the count had finished, and read and saved again when the vector worker reaches its pause point, so a vector being read when Pause was pressed is included; Resume, turning indexing on and deleting the index clear them; nothing is read until Resume | `thePauseAndItsNumbersSurviveARestartAndNothingRunsUntilResume`; `thePausedNumbersIncludeTheVectorBeingReadWhenPauseWasPressed`; `pausedBeforeTheCountFinishedShowsNoNumbersAfterARestart` |
| Deletions while paused | A deleted chat's entries are removed at once on the index's thread; a restore removes chats that are gone | `aChatDeletedOrRemovedByARestoreWhilePausedLeavesTheIndexAtOnce` |
| Whether the model is available while paused | A catch-up while paused (start, model change, restore) still checks the model and reports one that is missing; with a model, a vector pass loads it and stops at once, so a returning model clears its condition without anything being read | `aModelRemovedOrReturningWhilePausedIsStillNoticedWithoutIndexingAnything` |
| Which state the icon shows when the setting or a condition changes | Worked out again on every change, one at a time so an older answer never replaces a newer one; the model coming back never clears the user's pause | `theModelBeingUnavailableOrIndexingOffHidesThePausedIconWithoutClearingThePause`; `ConversationIndexingIconInstrumentedTest` |

### Changes during waits — Stage 9, Step 13

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Whether indexing may run now | One owner, `BackgroundIndexingPermit` (visible, background service, or closed; a process starts closed). The coordinator checks it before every catch-up, every queued chat, every message of a chat's update and every recovery, and the vector worker before every vector; while closed they stop at that point and keep every queue (a chat whose update was stopped is put back first, without reading the permit again, unless it was deleted or renamed away meanwhile), so no work is dropped. A refusal is remembered in the same locked step that a change of state reads it; a change to a state that may run after a refusal hands one "continue" task to the index's thread, after any task that found it could not run, so none is missed, and nothing is started when nothing waited | `BackgroundIndexingTest.aProcessStartsClosedAndOnlyBecomingAbleToRunAgainContinuesWaitingWork`; `theAppOpeningAtTheMomentIndexingFindsItMayNotRunIsNeverMissed`; `theAppOpeningWhileAVectorPassStopsForTheClosingIsNeverMissed`; `aProcessStartedForBackgroundWorkIndexesNothingUntilAScreenOpens`; `waitingVectorsAreNotReadWhileTheAppIsClosedWithoutTheService`; `aChangedChatSavedJustBeforeClosingWaitsForTheApp`; `closingStopsAChatPartWayThroughItsUpdateAndOpeningFinishesItFromThere`; `aRecoveryWaitsWhileTheAppIsClosedAndRunsWhenItOpens`; `openingContinuesWaitingWorkEvenWhenTheWaitingCatchUpCannotStartYet`; `theAppOpeningAtTheMomentAChatsUpdateIsStoppedNeverDropsTheChat`; `openingAndClosingTheAppWithNothingWaitingDoesNoIndexingWork`; `aChatDeletedWhileItsUpdateIsStoppedByTheClosingNeverComesBack` |
| Whether leaving the app starts the background service | Decided on the main thread while indexing may still run, from the same progress, pause and conditions as the header icon; only then does the state become service or closed. Work going on meanwhile continues, and is never dropped either way. A start is never tried later | `BackgroundIndexingTest.leavingTheAppDuringACatchUpStartsTheServiceAndOnlyThen`; `leavingWhileIndexingWorksDuringTheDecisionDropsNothingAndTheServiceContinuesIt`; `leavingWhileACatchUpIsStillBeingAdmittedKeepsItsWorkForTheApp` |
| A restore finishing while the app is closed | Chats it removed leave the index at once; its catch-up then finds indexing closed (unless the service runs) and waits without reading a chat or loading the model; the next opening, or the next process's first screen, starts it | `BackgroundIndexingTest.aRestoreFinishingWhileTheAppIsClosedRunsNothingUntilItOpens`; `aRestoreFinishingWhileTheAppIsClosedIsContinuedByTheNextProcess`; `chatsARestoreRemovedLeaveTheIndexAtOnceEvenWhileTheAppIsClosed`; `aCatchUpThatEndedInTheBackgroundLeavesALaterRestoreWaitingForTheApp`; `aRestoreWhileTheServiceRunsCountsAgainThenShowsTheRealPercentage` |
| Stops, refusals and failures of the service, including ones that arrive after the app opened again | Each closes indexing only if no screen opened meanwhile (one locked step); the notice is shown only when the service was what let indexing run, never after a later leave decided not to continue. A start still on its way when a later leave decides against it is told that leave's reason; a service whose start arrives after indexing was closed stops at its first update |  `BackgroundIndexingTest.stopsAndRefusalsThatArriveAfterTheAppOpenedAgainChangeNothing`; `aStartNoLongerWantedEndsWithTheLatestLeavesReason`; `aLateRefusalAfterTheUserPausedAndLeftShowsNoNotice`; `theServiceStopsForEachConditionAndNamesItsCause`; `reopeningBeforeTheServiceStartsAndItsLateStopKeepIndexingRunningInTheApp`; `ConversationIndexingServiceInstrumentedTest.openingTheAppStopsTheServiceAndRemovesItsNotification` |
| Whether work continues after Android's time limit or a refused start | Indexing closes at once; a read already running finishes and is stored, nothing more is read; opening the app continues from the index as it stands | `BackgroundIndexingTest.androidsTimeLimitLosesNoFinishedWorkAndOpeningTheAppContinuesWithoutReadingAnythingTwice`; `itNeverStartsWhenTheDaysTimeIsUsedUpAndIndexingWaitsForTheApp`; `afterTheProcessEndsTheNextStartContinuesFromTheLastFinishedPoint`; `ConversationIndexingServiceInstrumentedTest.androidsRealTimeLimitStopsTheServiceInTimeHoldsIndexingAndARestartIsRefused` |
| What the notification shows and whether the service stops | Worked out about every 10 seconds on the index's thread, after the work handed to it before, so a rebuild and the catch-up that follows it are seen together; a catch-up that handed over all its chats, whose vector passes stopped on a failure with no further pass asked for since the hand-over (the stop records the request count under the same lock as the requests, only a stop after the hand-over counts, a pass ending in an Error counts as failed, and a chat still queued is never a stall), stops the service instead of keeping the phone awake, and leaving the app starts none for it; closing while a catch-up is going always lets the next opening continue it | `BackgroundIndexingTest.theServiceStopsForEachConditionAndNamesItsCause`; `aCatchUpThatCannotGoOnAfterAFailureStopsTheServiceAndOpeningContinuesIt`; `aVectorFailureBeforeTheCatchUpHandedOverItsChatsIsNotAStall`; `aFailedPassWithAnotherAlreadyAskedForIsNotAStall`; `leavingWithAStalledCatchUpStartsNoService`; `leavingWithAStalledCatchUpStartsNoServiceAndOpeningContinuesIt`; `leavingAtTheHandOverWithAnOlderFailureStillContinuesInTheBackground`; `aRecoveryThatCannotRunNowLetsTheCatchUpGoOnOrStopRatherThanWait`; `aChatStillQueuedAfterTheHandOverIsNotAStall`; `aPassEndingInAnErrorIsAFailure`; `aStopAfterAnErrorNamesTheStageAndTheActualCauseAndKeepsTheWork`; `aPauseOrTurningIndexingOffAfterAFailureIsNeverOverriddenByTheOpening`; `turningIndexingOffOrDeletingTheIndexStopsTheService`; `aMissingModelStopsTheServiceWithItsOwnCause`; `aRebuildShowsRebuildingAndAFailedOneStopsTheService`; `ConversationIndexingServiceInstrumentedTest.theNotificationShowsTheRealContentForEachStateAndGoesWhenTheCatchUpEnds` |
| The user's pause across closing and opening | Separate from the permit and saved; opening the app continues only what the pause allows | `BackgroundIndexingTest.theUserPausingBeforeClosingStartsNothingAndAPausedIndexNeverRestartsByItself` |
| Whether work the user started is running | Read from Android's list of the app's running services when the app is left; indexing then waits for the next opening | `BackgroundIndexingTest.workTheUserStartedComesFirstAndIndexingWaitsForTheAppWithoutANotice`; `workTheUserStartedChangesNothingWhileTheAppIsOpenOrTheServiceAlreadyRuns` |

### Changes during waits — Stage 10, Step 14

| Decision | Detect changes during the wait | Verification |
| --- | --- | --- |
| Which messages may be recalled, and their words | The chat is read through the index's chat reader when the search starts; a chat with a save in progress is not read and recall hands on nothing. A passage counts only while its message is eligible in that read and has the text fingerprint it was indexed from, so an index that has not caught up with an edit never serves the old words | `RecallEngineTest.anEditedMessageIsFoundOnlyByItsNewTextAndANotYetUpdatedIndexNeverServesTheOldText`; `aChatThatCannotBeReadNowIsSkippedNotSearchedFromTheIndexAlone` |
| Passages, words and vectors read while indexing writes | Each read is one statement; a vector is read joined to its own passage row, so it always belongs to that passage's text, and only made vectors of the current model and size are compared; rows replaced between reads are dropped by the text check above | `StoreRecallIndexInstrumentedTest.anEditIndexedDuringTheSearchIsNeverRecalledInItsOldWords`; `theIndexIsReadWithoutTextAndOnlyMadeVectorsOfTheModelAreCompared`; `RecallEngineTest.anotherModelsVectorsAreNeverCompared` |
| Whether an exchange may be handed on | Just before it is handed on, the chat's stored version, its count of reported changes and any save still on its way are compared with the first read; if anything changed the chat is read once more, and every eligible message of the exchange must still be eligible there with the same text (a repeated permanent ID counts by its first eligible copy, as the index does). A hidden message or an unfinished reply in the exchange leaves it out. Of identical exchanges, the latest copy that passes this check is kept | `RecallEngineTest.aMessageEditedOrDeletedWhileTheSearchRunsIsLeftOutBeforeResultsAreHandedOn`; `hiddenDeletedAndUnfinishedMessagesAreNeverRecalledNorTheirExchanges`; `aRepeatedPermanentIdIsCheckedAgainstTheCopyThatWasSearched`; `anIdenticalEarlierExchangeIsKeptWhenTheLaterCopyFailsItsFinalCheck`; `StoreRecallIndexInstrumentedTest.aMessageDeletedOrHiddenDuringTheSearchIsLeftOut` |
| The chat, or the whole index, deleted or recovered during a search | Index reads run under the index's shared access, so never while a recovery replaces the file; a recovery running or waiting is not waited for. Nothing is handed on and the search ends normally; an index or chat that cannot be read now, or a model read that fails, ends that part of the search without failing the send (a failed model read leaves keywords) | `StoreRecallIndexInstrumentedTest.theChatDeletedOrTheIndexDeletedDuringTheSearchGivesNothingAndNoError`; `theIndexClosedByARecoveryDuringTheSearchNeverFailsTheSearch`; `aRecoveryHoldingTheIndexIsNotWaitedForAndRecallHandsOnNothing`; `RecallEngineTest.anIndexThatCannotBeReadMidSearchHandsOnNothingInsteadOfFailingTheSend`; `aReaderThatFailsLeavesKeywordsAndASourceThatFailsSkipsRecallButNeitherFailsTheSend` |
| A restore marking every chat for refresh during a search | Rows that are still true are still used, because each is checked against the chat as stored now | `StoreRecallIndexInstrumentedTest.aRestoreMarkingEveryChatForRefreshDuringTheSearchChangesNothingThatIsStillTrue` |
| Code-based exchanges carried from the previous message | Kept in memory only; recorded only after a message is sent (Step 16); each is checked again against the chat as stored now, and against what the AI already reads, every time it would be carried | `RecallEngineTest.aCarriedExchangeIsCheckedAgainstTheChatAsStoredNow`; `RecallTimeAndContinuityTest.continuityKeepsAtMostThreeExchangesPerChatInMemoryAndIsClearedWhenAsked` |
| Whether the search finished within its time | Recall's allowance and the shared pre-send deadline are checked before and after every read; a search that runs past them hands on nothing, optional work (pieces, the helper's query, carried exchanges) does not start with less than the minimum left, and a long message looks up a bounded number of words | `RecallEngineTest.outOfTimeHandsOnNothingAndOptionalWorkDoesNotStartWithoutEnoughTimeLeft`; `aLongMessageLooksUpABoundedNumberOfWords`; `RecallTimeAndContinuityTest.theBudgetIsTheEarlierOfRecallsOwnAllowanceAndTheSharedDeadline` |

### External services and costs

- Obtain model catalogs, capabilities, voice lists, prices, and other provider-controlled information from the provider or an authorized live source. Do not hard-code data that providers can change.
- If a provider does not report a cost, show **Not Reported**; do not invent a price. Test fixtures may use clearly fictional values solely to check calculations.
- Do not add paid model calls, large investigations, or unnecessary subagent swarms by default. Choose tools and model tiers appropriate to the task. Ask before materially increasing paid usage, spawning multiple agents, or using a high-end subagent for exploratory work.

## 5. UI, styles, and theme compatibility

Before editing a UI screen:
1. Read the applicable sections of `ui-style-guide.md` and its shared style definitions.
2. Read `ui-style-adoption.md` for that screen's verified adoption and legacy status.
3. Inspect the screen's current XML/code and the shared components it actually uses.

**Use approved shared styles as the source of visual properties.** Layouts describe structure, relationships, IDs, and content references. Colors, sizes, spacing, typography, shapes, alignment, and other appearance belong in shared styles, dimensions, and theme resources, not repeated XML attributes or Kotlin literals. Never copy a screen, layout, code, values, or an approach from an existing file as a shortcut or precedent unless the owner explicitly directs that specific copying. Reusing approved shared components and styles through their intended interfaces is different from copying an implementation. The style guide, not another screen, defines appearance. If the guide lacks a needed reusable style, create an appropriate shared style and add it to the guide without asking about an already settled visual convention; seek approval first if the new appearance changes another screen's approved experience.

Reuse established style families for headers, rows, buttons, fields, dialogs, icons, validation, imagery, and loading/empty/error states. Keep unique product behavior local when it belongs only to one screen. Shared code is an implementation choice, not a reason to remove or relocate an approved control.

When changing a shared component, identify which screens use it, whether it shares appearance, structure, behavior, or data, and the visible effect on each. Obtain approval for changes that affect other screens. If asked to use a screen as a reference, provide a component map covering its header, title, actions, images, fields, labels, hints, validation, counters, rows, selectors, switches, buttons, dialogs, loading states, and spacing where applicable. For each, show the approved shared style, what the reference and target actually use, the intended change, and any intentional difference. Do not clone the entire screen or use an unaudited/partial/legacy screen as the design authority.

A screen is only "Shared" or fully converted when its repeated visual components use the approved design system. Update `ui-style-adoption.md` whenever approved conversion status changes; never claim a visual audit or device check that was not done.

AMOLED-specific styling and palette/theme redesign work are paused until the owner reauthorizes them. Preserve working existing code without expanding the per-screen recoloring mechanism. New UI should remain compatible with shared theme colors and future palette changes.

Do not include the app's name in user-facing copy or wording proposals; name the feature instead.

## 6. Human-centered content and memory

The app must remain clear, predictable, respectful, and user-controlled even when someone is stressed or exhausted. Never replace significant personal content with a generic activity label or success marker when the meaning matters. Preserve the substance, context, and purpose needed for the person to recognize what was entrusted to the app.

Do not add crisis, safety, or hotline text to instructions that the memory system sends to an AI. The user's selected model handles its response under its own instructions.

For feature-specific memory or conversation-recall work, follow the assigned active plan's scope, approvals, and testing criteria. Do not replace the Summarizer's continuity purpose or existing memory behavior merely because another architecture would be easier.

## 7. Failures, evidence, and logs

User-facing failures should explain **what failed, the known cause, what was preserved, and what the user can do next**. Distinguish file-type mismatch, damage, encryption/key problems, permissions, unsupported formats, empty files, and unknown causes where the app can actually tell them apart. Do not label data corrupt or incompatible without evidence.

Before requesting more crash reports, backup files, or diagnostic artifacts, verify that supplied evidence is readable and usable. If not, say exactly why and what would work instead.

**All logging changes require owner approval first.** This covers adding, moving, removing, or rewording any Logcat, persistent event, error, voice/Whisper performance, or other diagnostic line. State the proposed content, purpose, and destination. Wanting better diagnostics does not itself authorize adding logs. A pure revert restoring the exact previously approved line is allowed.

## 8. Branches, checks, reviews, and merges

### Implementation workflow

For code work, use this sequence:

1. Fetch `main`, confirm its current commit, and branch from that commit unless the owner explicitly names a different target. Inspect relevant code, finish the assigned changes, and review the full diff yourself.
2. Run the full local checks once, when the work is complete, and fix failures: `./gradlew testDebugUnitTest assembleBeta assembleDebugAndroidTest` (one invocation, so build outputs are shared). Do not open a code PR with known failed local checks.
3. Open one pull request once the work is complete, required owner decisions are resolved, and local checks pass.
4. Android Checks must pass on **the final commit**. Investigate and fix real failures, running the focused tests for the fix before pushing.
5. Codex reviews each PR automatically when it is opened (see **Codex reviews** below). Validate each finding against the actual code and owner-approved behavior, fix valid findings, and reply with the reason for any finding not fixed.
6. Do not merge work whose checks are still running or failed. Merge only with the owner's explicit permission for that PR, into **`main`** (or a different integration branch the owner designates), using an ordinary merge method and no force-push.

**One pull request per session.** Combine all changes made in one session into a single pull request, including documentation, instruction and code changes. Split them only for an extremely good reason, and state that reason in each PR.

**Codex reviews:** Codex usage is limited. Codex starts a review automatically when a pull request is opened, so open a PR only once implementation, self-review and the necessary testing are finished. Never comment `@codex review` to request that opening review. After fixing valid Codex findings and rerunning the relevant checks, comment `@codex review` on the corrected commit without asking, and repeat until Codex reports no remaining valid findings. Request no other Codex review without the owner's explicit approval. Required testing and CI verification apply in full whether or not Codex reviews.

**Documentation-only PRs:** do not run local checks or Android builds when every change is documentation; inspect the complete replacement text, links, scope, and diff instead, and never claim tests were executed when they were not. A PR containing both documentation and code follows the code workflow above.

After opening a PR, do not run repetitive status checks or schedule watchers. Check while CI or review action is pending, then stop. If only the owner can decide the remaining blocker, ask once and leave the PR intact.

**Testing during development:** run focused tests for the code you changed; widen them when a change touches shared code. Do not rerun unchanged tests against unchanged code or rebuild the whole app after every small edit. The full local checks above run once, before the PR opens. A fix made after that (for CI, Codex or review) runs the focused tests for what it changed, widened for shared code, but not the full local checks again: Android Checks reruns the full unit tests, both builds and the device tests on every pushed commit, and must pass on the final commit, never on a different commit's run.

**CI efficiency and reliability:** Android Checks runs once per pull-request commit and on `main`. Avoid redundant builds and runs while preserving all required coverage. Distinguish infrastructure failures (runner, emulator, network, rate limits) from application and test failures using the run's logs and diagnostics. Record each infrastructure failure in the open GitHub issue **CI Reliability Log** (run link, commit, job and step, exact error, diagnosis, resolution), adding to an existing failure signature instead of duplicating it. Investigate recurring failures before rerunning; rerun a job at most once. Never remove, disable or weaken a test to get a passing result.

**Completion reporting:** Separate what was changed, which tests actually ran, the GitHub/CI/review status, and any behavior that still needs on-device confirmation. Green CI alone does not prove an observed runtime symptom is fixed; the owner verifies the real device behavior.

### App build identity

`main` continues to produce the side-by-side Beta app: use `assembleBeta`, package `com.soulphosphor.phosphorshines.beta`, and label `Phosphor Shines Beta`. The branch name does not change the app identity. Do not substitute the old client, a standard release build, or the old pre-release package. Automatic publishing from `main` uses the Beta workflow; the legacy release workflow is not an automatic publishing path for current work.

### Build environments

In Claude Code cloud sessions, use `./claude-cloud-android-setup.sh` once per fresh environment to provision Android build tools, then run the documented Gradle checks. Other environments must verify their own tool availability; do not assume the same setup works everywhere. If a full local build is impractical, explain the limitation and use available focused checks plus GitHub Actions. Never claim an unrun test passed.

If a GitHub HTTPS remote has no usable credentials, do not waste task time retrying authentication. Use an authorized connector or leave the verified change for a credentialed environment.

## 9. Repository privacy and metadata

Coding conversations stay private. Do not put private chats, health or emotional information, account details, private prompts, session IDs, AI conversation links, model attribution, or AI co-author trailers in commits, PRs, issues, source, documentation, release notes, or tags.

Commit and PR text should describe the technical change and validation only. Inspect changed files and metadata before publishing.

Never write an attribution footer or AI link into GitHub text. If a tool appends one automatically and it cannot be removed, ignore it: do not report it to the owner, and never delay a review, check or merge over it.

## 10. Task-specific documentation

This file is the navigation point, not an encyclopedia. Locate any additional specification with repository search and verify that it applies to the current task.

| Work | Relevant source |
| --- | --- |
| All memory work: saved memories, Memory Browser, Memory Assistant, context assistance, conversation indexing and recall | `conversation_recall_plan.md`, the single authoritative memory development plan: read Part M (build order, status, decisions), G.19, the **assigned** stage or work order, its referenced design sections, and the current source files. Only explicitly approved choices are binding; do not build withdrawn or later-stage proposals. |
| Background design for saved-memory search and later saved-memory features | `Memory System/dynamic_memory_plan.md`: reference only (design and D-decisions); Part M of the memory development plan sets order and status |
| UI and theme-ready styles | `ui-style-guide.md` and `ui-style-adoption.md` |
| UI redesign | `ui-redesign-plan.md` |
| Document attachments | `document-includes-plan.md` |
| Local speech | `whisper-local-plan.md` |
| API speech-to-text | `api-voice-service-plan.md` |

Feature plans specify intended outcomes and work orders. Current code shows the implementation. If they disagree, identify the exact discrepancy; do not quietly choose a side or restart a completed feature. Historical documents are research only unless a current approved work order explicitly relies on them.

## 11. Keep these instructions clean

Keep `CLAUDE.md` short, durable, and operational.

- When a rule changes, **replace the old rule**. Do not append "previously X, now Y," competing rulings, or decision histories.
- Do not add dates to standing rules unless an actual deadline or version boundary changes how an agent must act.
- Keep temporary priorities, bugs, investigations, research, feature architecture, and implementation status in the task, issue, or feature document.
- If a rule's current validity is uncertain, ask rather than preserve contradictory versions as instructions.
- Use Git history to recover old decisions when genuinely needed; agents should not have to read that history to carry out current work.
