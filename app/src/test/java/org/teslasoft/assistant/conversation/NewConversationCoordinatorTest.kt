package org.teslasoft.assistant.conversation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewConversationCoordinatorTest {
    private val source = source("conversation/NewConversationCoordinator.kt")
    private val dialog = source("ui/fragments/dialogs/AddChatDialogFragment.kt")

    @Test
    fun coordinatorOwnsStableProvisionalUuidAndDoesNotSaveAListRow() {
        assertTrue(source.contains("UUID.randomUUID().toString()"))
        assertTrue(source.contains("ConversationMode.PENDING_KEY, true"))
        assertTrue(source.contains("putString(\"chat\", \"[]\").commit()"))
        assertFalse(source.contains("Hash.hash(request.name)"))
        assertFalse(source.contains("addChat("))
    }

    @Test
    fun legacyAddChatUsesTheSharedCoordinator() {
        assertTrue(dialog.contains("NewConversationCoordinator(requireActivity()).createPendingConversation"))
        assertFalse(dialog.contains("chatPreferences?.addChat"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
