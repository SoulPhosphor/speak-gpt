package org.teslasoft.assistant.preferences.chatnavigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationSourceContractTest {
    @Test fun productionSnapshotUsesMetadataOnlyChatListRead() {
        val source = repositorySource().readText()
        assertTrue(source.contains("getChatListResult(app, includeFirstMessage = false)"))
        assertFalse(source.contains("getChatByIdResult"))
        assertFalse(source.contains("getChatById("))
    }

    @Test fun organizationMutationsUseSynchronousCommitsUnderTheSharedLock() {
        val source = repositorySource().readText()
        assertTrue(source.contains("synchronized(ChatPreferences.CHAT_LIST_LOCK)"))
        assertTrue(source.contains("editor.commit()"))
        assertFalse(source.contains("getChatList(context"))
    }

    private fun repositorySource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/preferences/chatnavigation/ChatNavigationRepository.kt"
        return listOf(
            File(relative), File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        ).firstOrNull { it.isFile } ?: error("Could not locate ChatNavigationRepository.kt")
    }
}
