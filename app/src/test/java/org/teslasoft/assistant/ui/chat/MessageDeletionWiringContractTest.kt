package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message deletion settles the conversation at once on the main thread and
 * never waits for stopped work; that work is fenced off from the chat.
 */
class MessageDeletionWiringContractTest {
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy { source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt") }

    private fun function(name: String): String {
        val start = activity.indexOf("private fun $name(")
        assertTrue("$name not found", start >= 0)
        val next = activity.indexOf("\n    private fun ", start + 1)
        return activity.substring(start, if (next < 0) activity.length else next)
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
        assertTrue(retire.contains("claimTerminalState()"))
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
    }

    @Test fun everyLateWriterOfARetiredReplyIsFenced() {
        for (name in listOf(
            "restoreUIState", "putMessage", "markLastAssistantStreaming", "settlePendingRegeneration",
            "showPendingContextHelperFailure", "showTerminalFailure"
        )) {
            assertTrue("$name is not fenced", function(name).contains("if (turnFence.isStale()) return"))
        }
        // Both reply scopes carry the fence.
        assertFalse(activity.contains("parseMessageScope = CoroutineScope(Dispatchers.Main)\n"))
        assertTrue(activity.contains("parseMessageScope = CoroutineScope(Dispatchers.Main + turnFence.element())"))
    }

    @Test fun usageIsClaimedBeforeAnyWaitAndAttachedToTheRowChosenThen() {
        val finalize = activity.substringAfter("private suspend fun finalizeLifecycleTerminal(")
            .substringBefore("private class ClaimedTerminal(")
        assertTrue(finalize.contains("if (turnFence.isStale()) return"))
        assertTrue(finalize.indexOf("usageCarrier()") < finalize.indexOf("completeClaimedTerminal("))
        assertTrue(activity.contains("if (currentLifecycle === r) currentLifecycle = null"))
    }

    @Test fun everyChatScreenSaveIsOrdered() {
        val save = activity.substringAfter("    private fun saveSettings(").substringBefore("    private fun saveSettingsInBackground(")
        assertTrue(save.contains("order.writeIfNewest(chatId, seq"))
    }
}
