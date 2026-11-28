/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.conversation.reply

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A reply already sent survives its chat screen: the reply's own classes
 * never hold the screen, the screen hands the reply off before tearing down,
 * and only Stop and deletion end it early.
 */
class ReplyProtectionWiringContractTest {
    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path")).firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val chat by lazy { source("ui/activities/ChatActivity.kt") }

    @Test fun theReplysOwnClassesNeverHoldAScreen() {
        for (file in listOf(
            "ChatRequestHooks.kt", "ReplyRun.kt", "RequestRelay.kt", "DetachedReplyHost.kt",
            "ReplyRunRegistry.kt", "PendingToolTurns.kt", "ReplyPiece.kt"
        )) {
            val text = source("conversation/reply/$file")
            assertFalse("$file refers to ChatActivity", text.contains("ChatActivity"))
            assertFalse("$file uses a screen's lifecycle scope", text.contains("lifecycleScope"))
            assertFalse("$file refers to an Activity", Regex("\\bActivity\\b").containsMatchIn(text))
        }
    }

    @Test fun closingTheScreenHandsTheReplyOffBeforeStoppingTheScreensWork() {
        val destroy = chat.substringAfter("public override fun onDestroy() {")
        assertTrue(destroy.indexOf("handOffRunningReply()") in 0 until destroy.indexOf("killAllProcesses()"))
        val handOff = chat.substringAfter("private fun handOffRunningReply() {").substringBefore("\n    }\n")
        // Waiting for an image approval: parked, not handed off.
        assertTrue(handOff.indexOf("run.park()") in 0 until handOff.indexOf("run.handOff("))
        assertTrue(handOff.contains("saveSettings()"))
    }

    @Test fun hangUpAndHandsFreeOffKeepASentReplyWhileStopEndsIt() {
        assertTrue(chat.contains("cancelAllAiActivity(\"notification Hang Up action\", keepSentReply = true)"))
        assertTrue(chat.contains("cancelAllAiActivity(\"conversation button tap (stop hands-free)\", keepSentReply = true)"))
        for (stop in listOf(
            "cancelAllAiActivity(\"mic button tap on this screen\")",
            "cancelAllAiActivity(\"mic button touch on this screen (mid-generation)\")",
            "cancelAllAiActivity(\"conversation button tap on this screen\")"
        )) assertTrue(stop, chat.contains(stop))
        val keep = chat.substringAfter("private fun endVoiceKeepingReply() {").substringBefore("\n    }\n")
        for (replyScope in listOf("processRecordingScope", "parseMessageScope", "setupScope", "onSpeechResultsScope")) {
            assertFalse("ending voice must not stop the reply's $replyScope", keep.contains(replyScope))
        }
        assertTrue(chat.contains("if (replyReadbackEnded) return"))
    }

    @Test fun deletionRenameAndRestoreReachTheReplyRegistry() {
        assertTrue(source("preferences/chatdeletion/ChatDeletionCoordinator.kt").contains("ReplyRunRegistry.markChatsDeleted(it)"))
        assertTrue(source("preferences/ChatPreferences.kt").contains("ReplyRunRegistry.rename(oldId, newId)"))
        assertTrue(source("preferences/backup/ChatSetReplacementCoordinator.kt").contains("ReplyRunRegistry.discardAll()"))
    }

    @Test fun aReopenedChatReconnectsAndKeepsTheLiveRowAsItIs() {
        assertTrue(chat.contains("val liveReplyRowId = org.teslasoft.assistant.conversation.reply.ReplyRunRegistry.active(chatId)"))
        val attach = chat.substringAfter("private fun attachToRunningReply() {").substringBefore("\n    }\n")
        assertTrue(attach.contains("progress?.setOnClickListener { run.stopByUser() }"))
        assertTrue(attach.contains("run.attach("))
    }

    @Test fun aReconnectedRegenerationKeepsItsEarlierVersions() {
        val attach = chat.substringAfter("private fun attachToRunningReply() {").substringBefore("\n    }\n")
        assertTrue(attach.contains("takeOverRegenerationOf(rowId)"))
        val finished = attach.substringAfter("override fun replyFinished() {")
        assertTrue(finished.indexOf("settlePendingRegeneration()") in 0 until finished.indexOf("restoreUIState()"))
        val takeOver = chat.substringAfter("private fun takeOverRegenerationOf(rowId: String) {").substringBefore("\n    }\n")
        assertTrue(takeOver.contains("pendingRetryVariants = org.teslasoft.assistant.ui.chat.RegenerationRecovery.historyOf(pending.original)"))
    }

    @Test fun aMessageDeletionDetachesTheOldDisplayBeforeTheNextTurn() {
        val retire = chat.substringAfter("private fun retireRunningWorkForDeletion() {")
        assertTrue(retire.indexOf("attachedReply = null") < retire.indexOf("run.stopByUser()"))
        assertTrue(retire.contains("detachReplyDisplay?.invoke()"))
        assertTrue(chat.contains("if (attachedReply !== run || isFinishing || isDestroyed) return"))
    }

    @Test fun reattachingDoesNotTransferTheReplysRowsOrPersistenceOwner() {
        val run = source("conversation/reply/ReplyRun.kt")
        val attach = run.substringAfter("fun attach(next: ReplyRunHost): () -> Unit {").substringBefore("\n    }")
        assertTrue(attach.contains("screenObserver = next"))
        assertFalse(attach.contains("host ="))
        assertTrue(run.contains("screenReleased?.await()"))
        assertTrue(run.indexOf("host.awaitSaved()") < run.indexOf("ended = true", run.indexOf("private suspend fun finishOnOwn")))
    }

    @Test fun startupRejectsTheWholeSnapshotWhenItsReplyFinishedDuringTheRead() {
        assertTrue(chat.contains("ReplyRunRegistry.capture(preparedChatId)"))
        val init = chat.substringAfter("private fun initializeChatUi(")
        assertTrue(init.indexOf("ReplyRunRegistry.isCurrent(") < init.indexOf("chatId = prepared.chatId"))
        assertFalse(chat.contains("messages[index] = finished"))
    }
}
