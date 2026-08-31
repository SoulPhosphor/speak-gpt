package org.teslasoft.assistant.preferences.chatsearch

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchSourceContractTest {
    private fun root(): File = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        .firstOrNull { it.isDirectory } ?: error("src/main not found")

    private fun source(relative: String): String = File(root(), relative).readText()

    @Test fun searchableHistoryWritesShareOneGuardedRevisionCommit() {
        val preferences = source("java/org/teslasoft/assistant/preferences/ChatPreferences.kt")
        val save = preferences.substringAfter("fun saveChatHistory(").substringBefore("// The upstream fork")

        assertTrue(save.contains("ChatSearchIndexJournal.get(context).record(chatId, revision)"))
        assertTrue(save.indexOf(".putString(\"chat\"") < save.indexOf("SEARCH_REVISION_KEY"))
        assertTrue(save.contains("if (synchronous || searchableChanged) editor.commit()"))
        assertTrue(preferences.contains("saveChatHistory(context, chatId, list, synchronous = true)"))
        assertTrue(preferences.contains("fun importChatHistoryJson"))
        assertFalse(preferences.substringAfter("fun importChatHistoryJson").substringBefore("/**").contains(
            "SecurePrefs.get(context, \"chat_"
        ))
    }

    @Test fun titleWritesAreJournaledAndDeletionInvalidatesDerivedRows() {
        val preferences = source("java/org/teslasoft/assistant/preferences/ChatPreferences.kt")
        val deletion = source("java/org/teslasoft/assistant/preferences/chatdeletion/ChatDeletionCoordinator.kt")
        val startup = source("java/org/teslasoft/assistant/app/MainApplication.kt")

        assertTrue(preferences.contains("ChatSearchIndexJournal.get(context).record(oldId, revision)"))
        assertTrue(preferences.contains("scheduleTitleRefresh(oldId, titleSearchRevision!!)"))
        assertTrue(deletion.contains("scheduleChatsDeleted(it)"))
        assertTrue(startup.contains("ChatSearchIndexManager.get(this).ensureReady()"))
    }
}
