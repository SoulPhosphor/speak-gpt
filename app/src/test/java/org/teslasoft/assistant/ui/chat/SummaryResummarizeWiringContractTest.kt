/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resummarize-on-change and the Compact progress dialog (owner rulings,
 *  Oct 4 2026). */
class SummaryResummarizeWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun editAndRegenerateBothAskBeforeResummarizing() {
        val edit = activity.substring(activity.indexOf("override fun onMessageEdited()"),
            activity.indexOf("private fun askToResummarizeEditedSection"))
        assertTrue(edit.indexOf(".fromJson(") < edit.indexOf("syncChatProjection()"))
        assertTrue(edit.contains("cancelSummarizingAndWait()"))
        assertTrue(edit.contains("askToResummarizeEditedSection(before)"))
        assertTrue(activity.contains("withResummarizeDecision(position) { onRetryClick() }"))
        assertTrue(activity.contains("if (regenerateLockKind(position) != null) return"))
    }

    @Test
    fun alwaysResummarizeIsOnByDefaultAndAChatCanRememberItsChoice() {
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        assertTrue(prefs.contains("getGlobalString(\"summarizer_always_resummarize\", \"true\") == \"true\""))
        val perChat = source("src/main/java/org/teslasoft/assistant/preferences/PerChatSettingKeys.kt")
        assertTrue(perChat.contains("\"summary_resummarize_choice\""))
        val layout = source("src/main/res/layout/activity_summarizer_settings.xml")
        assertTrue(layout.indexOf("switch_summarizer_always_resummarize") > layout.indexOf("switch_summarizer_new_chats"))
    }

    @Test
    fun compactCancelKeepsNothingAndHasNoSetting() {
        assertTrue(activity.contains("savePartialOnCancel = false,"))
        assertFalse(source("src/main/res/layout/activity_summarizer_settings.xml").contains("compacting_cancel"))
        assertTrue(activity.contains("if (renderCompactionDialog(state)) return"))
        // After "Compaction complete!", Cancel undoes the compaction; Okay keeps it.
        assertTrue(activity.contains("summarizerController?.discardFinishedCompaction()"))
        assertTrue(activity.contains("summarizerController?.keepFinishedCompaction()"))
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertTrue(controller.contains("withContext(NonCancellable) { restoreRunLock(prefs, startingLock) }"))
    }

    @Test
    fun aCompactFailureShowsTheErrorBoxAndLengthIsNeverAFailure() {
        assertTrue(activity.contains("if (watching) showCompactionFailure(state)"))
        assertTrue(activity.contains(".setNeutralButton(R.string.title_summarizer_settings)"))
        assertTrue(activity.contains(".setNegativeButton(R.string.btn_msg_retry)"))
        // A reply that reached the length limit is saved as it is (owner
        // ruling, Oct 4 2026): length is never treated as a failure.
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertFalse(controller.contains("finishReason?.value == \"length\""))
        // A failed compaction keeps the batches that finished.
        assertTrue(controller.contains("prefs.commitManualCompaction(summary, folded, false, folded)"))
        assertTrue(controller.contains("partialSavedMessages = folded - operationStartFolded"))
    }

    @Test
    fun deletingCompactedMessagesAsksToRecompact() {
        assertTrue(activity.contains("askToRecompactAfterDelete(compactedBefore)"))
        assertTrue(activity.contains("askToRecompactAfterDelete(manualBoundaryBefore)"))
        assertTrue(activity.contains(".setNeutralButton(R.string.compaction_edit_summary)"))
        assertTrue(activity.contains("startManualCompaction(snapshot.copy(entries = snapshot.entries.take(boundary)), fromScratch = true)"))
    }
    @Test fun makeCurrentUsesTheEditDecisionPathAndSummaryOnlyLocksDoNotBlockIt() {
        val change = activity.substring(activity.indexOf("override fun onMakeVersionCurrent"),
            activity.indexOf("private fun promoteVersionAt"))
        assertTrue(change.contains("changeCanonicalVersion(position, display"))
        assertTrue(change.contains("finishSummarySourceChange(before)"))
        assertTrue(change.contains("cancelSummarizingAndWait()"))
        assertFalse(change.contains("if (condensedRegenerationLockKind(position) != null)"))
        val adapter = source("src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt")
        val promote = adapter.substring(adapter.indexOf("btnVersionPromote?.let"), adapter.indexOf("private fun showVersion"))
        assertTrue(promote.contains("== CondensedRegenerationLock.Kind.COMPACTION"))
    }

    @Test fun branchRegenerateMarksAnAffectedCompactRangeAndAsksWhenTheReplyFinishes() {
        val branch = activity.substring(activity.indexOf("override fun onRegenerate"), activity.indexOf("private fun truncateAfter"))
        assertTrue(branch.contains("if (position < boundary)"))
        assertTrue(branch.contains("setCompactionStale(true)"))
        assertTrue(branch.contains("pendingRecompactBoundary = boundary"))
        assertTrue(activity.contains("askToRecompactAfterDelete(boundary, sourceChanged = true)"))
        assertTrue(activity.contains("realignCondensedBoundaries(index + 1, end)"))
        assertTrue(activity.contains("pendingRetryMessageId?.let { last[org.teslasoft.assistant.preferences.MessageIdentity.KEY] = it }"))
    }

    @Test fun inFlightGuardStopsInsteadOfRestartingBeforeTheChangeDecision() {
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertTrue(controller.contains("!SummarySections.sourceStillCurrent(owned, context, latest)\n        ) return false"))
        assertTrue(controller.contains("!SummarySections.canCommitReplacement(replacing, target)"))
        assertTrue(activity.contains("if (summarySourceChangePending || pendingRetryMessageId != null) return"))
        val answer = activity.substring(activity.indexOf("private fun saveResummarizeAnswer"), activity.indexOf("private fun showResummarizeDialog"))
        assertTrue(answer.contains("val updated = live.map"))
    }

    @Test fun aPreDispatchFailureAlsoFinishesTheReplyReplacementAndReleasesSummarizing() {
        val terminal = activity.substring(activity.indexOf("private fun showTerminalFailure"),
            activity.indexOf("private fun modelFacingContent"))
        assertTrue(terminal.contains("mergePendingRetryVariants()"))
        val merge = activity.substring(activity.indexOf("private fun mergePendingRetryVariants"),
            activity.indexOf("private suspend fun markLastAssistantDone"))
        assertTrue(merge.contains("pendingRetryMessageId = null"))
        assertTrue(merge.contains("summarizerCycle()"))
    }

}
