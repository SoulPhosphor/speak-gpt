package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message deletion settles the conversation at once on the main thread and
 * never waits for stopped work; that work is fenced off from the chat.
 */
class MessageDeletionWiringContractTest {
    @Test fun deletionWithdrawsRegenerationRecoveryBeforeItsLifecycleCanClose() {
        assertTrue(function("finishPendingRegeneration").contains("if (holdSavesForDeletion) preferences?.clearPendingRegeneration()"))
        val merge = function("mergePendingRetryVariants")
        assertTrue(merge.indexOf("last[ChatAdapter.KEY_IMAGE_PROGRESS] == true") < merge.indexOf("pendingRetryVariants = null"))
        val retire = function("retireRunningWorkForDeletion")
        assertTrue(retire.lastIndexOf("settlePendingRegeneration()") > retire.indexOf("applyImageTerminal("))
        assertTrue(retire.indexOf("removeImageConfirmationCard()") < retire.indexOf("applyCancelledReplyPlan(plan)"))
        assertTrue(function("requestImageConfirmation").contains("if (pendingImageConfirmation === decision)"))
    }
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy { source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt") }
    /** A reply's usage and lifecycle bookkeeping lives in ReplyRun. */
    private val run by lazy { source("src/main/java/org/teslasoft/assistant/conversation/reply/ReplyRun.kt") }

    private fun runFunction(name: String): String {
        val start = listOf("internal fun $name(", "internal suspend fun $name(", "private fun $name(", "private suspend fun $name(")
            .map { run.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        assertTrue("$name not found", start >= 0)
        val next = Regex("\n    (internal|private) (suspend )?(fun|class) ").find(run, start + 1)?.range?.first
        return run.substring(start, next ?: run.length)
    }

    private fun function(name: String): String {
        val start = listOf("private fun $name(", "private suspend fun $name(")
            .map { activity.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        assertTrue("$name not found", start >= 0)
        val next = Regex("\n    private (suspend )?fun ").find(activity, start + 1)?.range?.first
        return activity.substring(start, next ?: activity.length)
    }

    @Test fun deletingMessagesNeverWaitsAndNeverPutsRowsBack() {
        for (body in listOf(function("deleteMessageRange"), function("retireRunningWorkForDeletion"))) {
            assertFalse(body.contains(".join()"))
            assertFalse(body.contains(".await()"))
            assertFalse(body.contains("cancelSummarizingAndWait"))
            assertFalse(body.contains("withRequestsStopped"))
            assertFalse(body.contains("saveSettings(synchronous = true)"))
            assertFalse(body.contains("messages.addAll("))
        }
        val delete = function("deleteMessageRange")
        assertTrue(delete.contains("retireRunningWorkForDeletion()"))
        assertTrue(delete.contains("parallelChat.keepDeletedGroups(removed)"))
        assertTrue(delete.contains("saveSettingsInBackground()"))
        // Saves the reused helpers make while settling are covered by the one
        // background save, never run on the main thread.
        assertTrue(delete.indexOf("holdSavesForDeletion = true") < delete.indexOf("retireRunningWorkForDeletion()"))
        assertTrue(delete.indexOf("holdSavesForDeletion = false") < delete.indexOf("saveSettingsInBackground()"))
    }

    @Test fun bulkDeletionAlsoNeverWaitsOrRestores() {
        val dialog = function("deleteSelectedMessages")
        assertFalse(dialog.contains("withRequestsStopped"))
        assertTrue(dialog.contains("parallelChat.abandonForDeletion()"))
        assertTrue(dialog.indexOf("holdSavesForDeletion = true") < dialog.indexOf("parallelChat.abandonForDeletion()"))
        assertTrue(dialog.indexOf("holdSavesForDeletion = false") < dialog.lastIndexOf("saveSettingsInBackground()"))
        val bulk = function("deleteBulkSelection")
        assertFalse(bulk.contains("saveSettings(synchronous = true)"))
        assertFalse(bulk.contains("messages.addAll("))
        assertTrue(bulk.contains("parallelChat.keepDeletedGroups("))
        assertTrue(bulk.contains("saveSettingsInBackground()"))
    }

    @Test fun theRetiredReplyIsSettledOnceAndItsImageIsDeliveredNowhere() {
        val retire = function("retireRunningWorkForDeletion")
        assertTrue(retire.indexOf("turnFence.retire()") < retire.indexOf("generationJobs.forEach { it.cancel() }"))
        assertTrue(retire.contains("CancelledReplyTerminal.plan("))
        assertTrue(retire.contains("claimTerminalState(keepCompletedUsage = true)"))
        assertTrue(retire.contains("ImageGenerationJobRegistry.discardDelivery(imageJob)"))
        assertTrue(retire.contains("summarizerController?.cancelSummarizing()"))
        // The cancellation handler uses the same plan.
        assertTrue(activity.contains("val plan = org.teslasoft.assistant.ui.chat.CancelledReplyTerminal.plan("))
    }

    @Test fun aDiscardedImageJobIsDeliveredNeitherToAScreenNorToStoredHistory() {
        val registry = source("src/main/java/org/teslasoft/assistant/imagegen/ImageGenerationJobRegistry.kt")
        val finish = registry.substringAfter("private suspend fun finish(").substringBefore("private suspend fun persistTerminalWithoutScreen(")
        val discard = finish.indexOf("if (record.deliveryDiscarded) return")
        assertTrue(discard >= 0)
        assertTrue(discard < finish.indexOf("listener.onImageJobFinished("))
        assertTrue(discard < finish.indexOf("persistTerminalWithoutScreen("))
        // The terminal still completes, so the job and its keep-alive end normally.
        assertTrue(finish.contains("record.terminal.complete(terminal)"))
        // A discarded job frees the chat's slot at once, and its own cleanup
        // can only remove itself, never a newer job for the same chat.
        val discardFn = registry.substringAfter("fun discardDelivery(").substringBefore("\n    }\n")
        assertTrue(discardFn.contains("if (jobs[key] === job) jobs.remove(key)"))
        assertTrue(finish.contains("if (jobs[key] === record) jobs.remove(key)"))
        assertFalse(finish.contains("jobs.remove(record.ownerKey ?: record.chatId)"))
    }

    @Test fun everyLateWriterOfARetiredReplyIsFenced() {
        for (name in listOf(
            "restoreUIState", "putMessage", "markLastAssistantStreaming", "settlePendingRegeneration",
            "showPendingContextHelperFailure", "showTerminalFailure", "attachActiveMemoryAttribution", "onContextHelperOutcome"
        )) {
            assertTrue("$name is not fenced", function(name).contains("if (turnFence.isStale()) return"))
        }
        // A retired reply never starts another request phase.
        assertTrue(activity.substringAfter("private suspend fun startLifecycle(").substringBefore("currentLifecycle?.let")
            .contains("if (turnFence.isStale()) throw CancellationException("))
        // Every scope that generates a reply carries the fence: typed replies,
        // voice replies and activation-prompt replies.
        assertFalse(activity.contains("parseMessageScope = CoroutineScope(Dispatchers.Main)\n"))
        assertTrue(activity.contains("parseMessageScope = CoroutineScope(Dispatchers.Main + turnFence.element())"))
        val replyLaunches = Regex("\\n([ ]*)(\\w+)\\?\\.launch \\{[\\s\\S]{0,600}?generateResponse\\(").findAll(activity).toList()
        // Typed, two voice paths, the activation prompt, and an image
        // approval resumed when the chat is opened again.
        assertEquals(5, replyLaunches.size)
        for (call in replyLaunches) {
            val scope = call.groupValues[2]
            val created = activity.substring(0, call.range.first).lastIndexOf("$scope = CoroutineScope(")
            assertTrue("$scope is not fenced", activity.startsWith("$scope = CoroutineScope(Dispatchers.Main + turnFence.element())", created))
        }
        // The deletion stops the job generating the reply, whichever scope runs it.
        assertTrue(function("generateResponse").contains("replyJob = currentCoroutineContext()[kotlinx.coroutines.Job]"))
        assertTrue(function("retireRunningWorkForDeletion").contains("replyJob\n"))
        // A reply finishing successfully stops if a deletion retired it meanwhile.
        val successes = activity.split("val providerDiagnostics = finalizeLifecycleSuccess()")
        assertEquals(4, successes.size)
        successes.drop(1).forEach {
            assertTrue(it.take(300).contains("if (turnFence.isStale()) throw CancellationException(\"Retired by message deletion\")"))
        }
    }

    @Test fun theErrorHandlerRechecksTheFenceAfterItsWaitAndTouchesOnlyItsOwnRow() {
        val handler = activity.substringAfter("            if (turnFence.isStale()) return\n            // This handler now writes")
            .substringBefore("        } finally {")
        // The wait (the usage record and lifecycle entry) is inside ReplyRun.recordFailure.
        assertTrue(runFunction("recordFailure").contains("finalizeLifecycleTerminal("))
        val wait = handler.indexOf("replyRun.recordFailure(e)")
        assertTrue(wait >= 0)
        assertTrue(handler.indexOf("replyTerminalPending = false") < wait)
        assertTrue(handler.indexOf("val failedRowBefore = messages.lastOrNull()") < wait)
        val stale = handler.indexOf("if (turnFence.isStale()) {")
        assertTrue(stale > wait)
        // Rechecked before any row is added or the last row is marked.
        assertTrue(stale < handler.indexOf("putMessage(\"\", true)"))
        assertTrue(stale < handler.indexOf("val failedIndex = messages.size - 1"))
        assertTrue(handler.contains("messages.indexOfFirst { it === failedRowBefore }"))
    }

    @Test fun usageIsClaimedBeforeAnyWaitAndAttachedToTheRowChosenThen() {
        val finalize = run.substringAfter("internal suspend fun finalizeLifecycleTerminal(")
            .substringBefore("internal class ClaimedTerminal(")
        assertTrue(finalize.contains("if (fence.isStale()) return"))
        assertTrue(finalize.indexOf("host.usageCarrier()") < finalize.indexOf("completeClaimedTerminal("))
        assertTrue(run.contains("if (state.currentLifecycle === r) state.currentLifecycle = null"))
    }

    @Test fun aCompletedRequestsUnrecordedUsageIsKeptWhenTheTurnEnds() {
        val claim = run.substringAfter("internal fun claimTerminalState(").substringBefore("state.currentUsageAttempt = null")
        assertTrue(claim.contains("completed = completed"))
        val complete = run.substringAfter("internal suspend fun completeClaimedTerminal(").substringBefore("val r = claimed.lifecycle")
        assertTrue(complete.contains("completeUsageRecordFrom("))
        // Deletion and both terminal handlers keep it; mid-turn hand-offs do not.
        assertTrue(function("retireRunningWorkForDeletion").contains("claimTerminalState(keepCompletedUsage = true)"))
        assertTrue((activity + run).split("keepCompletedUsage = true\n").size - 1 >= 2)
        val superseded = run.substringAfter("internal suspend fun startLifecycle(").substringBefore("pricingCatalog")
        assertFalse(superseded.contains("keepCompletedUsage"))
    }

    @Test fun usageOfDeletedRowsIsRetriedUntilTheUsageLogCommits() {
        val keep = runFunction("keepUsageOfDeletedRows")
        assertTrue(keep.contains("UsageLogStore.seedChecked(prefs, rows)"))
        assertTrue(keep.contains("if (kept) break"))
        assertTrue(activity.contains("override fun deletedGroupSettled(row: HashMap<String, Any>) {\n            keepUsageOfDeletedRows(listOf(row))"))
        val complete = run.substringAfter("internal suspend fun completeClaimedTerminal(").substringBefore("val r = claimed.lifecycle")
        assertTrue(complete.contains("keepUsageOfDeletedRows("))
        // Both deletion paths keep the removed rows' recorded usage the same way.
        assertTrue(function("deleteMessageRange").contains("keepUsageOfDeletedRows("))
        assertTrue(function("deleteBulkSelection").contains("keepUsageOfDeletedRows("))
    }

    @Test fun aStopWaitingOnParallelPreparationCannotCancelANewerTurn() {
        val controller = source("src/main/java/org/teslasoft/assistant/ui/chat/parallel/ParallelChatController.kt")
        val stopAll = controller.substringAfter("    fun stopAll() {").substringBefore("    fun stop(position: Int")
        assertTrue(stopAll.contains("stopJob = scope.launch {"))
        // Rechecked after the wait, before the unfiltered cancel.
        assertTrue(stopAll.indexOf("if (token != operationToken) return@launch") > stopAll.indexOf("preparingJob?.join()"))
        assertTrue(stopAll.indexOf("if (token != operationToken) return@launch") < stopAll.indexOf("coordinator.cancelOwned()"))
        val abandon = controller.substringAfter("    fun abandonForDeletion() {").substringBefore("\n    }\n")
        assertTrue(abandon.contains("stopJob?.cancel()"))
        assertTrue(abandon.indexOf("operationToken += 1") < abandon.indexOf("stopJob?.cancel()"))
    }

    @Test fun usageTakenByTheCompletionPathIsRecordedEvenIfCancelled() {
        // Every taker of the pending completed usage goes through the safe recorder.
        val takers = (activity + run).split("completePendingUsageRecord()").size - 1
        // The definition; on the chat screen two safe callers and the
        // no-carrier drop; with no screen open, the safe caller ending a reply,
        // the two safe callers ending a tool-call reply, and its no-carrier drop.
        assertEquals(8, takers)
        assertTrue(run.contains("recordTakenUsageSafely(row) { listOfNotNull(completePendingUsageRecord()) }"))
        assertTrue(runFunction("finishToolCallsWithoutScreen").split("recordTakenUsageSafely(").size - 1 == 2)
        val safe = runFunction("recordTakenUsageSafely")
        assertTrue(safe.contains("ReplyUsageCompletion.finish"))
        assertTrue(runFunction("completeClaimedTerminal").contains("ReplyUsageCompletion.finish"))
        assertTrue(safe.indexOf("val records = take()") < safe.indexOf("attachUsageRecords(carrier, records"))
        assertTrue(safe.contains("keepUsageOfDeletedRows("))
        assertTrue(safe.contains("if (fence.isStale()) throw CancellationException("))
        assertEquals(2, activity.split("replyRun.recordTakenUsageSafely(").size - 1)
    }

    @Test fun everyChatScreenSaveIsOrdered() {
        val save = activity.substringAfter("    private fun saveSettings(").substringBefore("    private fun saveSettingsInBackground(")
        assertTrue(save.contains("order.writeIfNewest(chatId, seq"))
    }
}
