package org.teslasoft.assistant.ui.drawer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatRevealContractTest {
    @Test fun newChatIsRevealedByItsDrawerPullingBackInsteadOfAWindowAnimation() {
        val controller = source("ui/drawer/ChatDrawerController.kt")
        val newChat = controller.substringAfter("private fun openNewChat()").substringBefore("companion object")
        assertTrue(newChat.contains(".addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)"))
        assertTrue(newChat.contains(".putExtra(EXTRA_REVEAL_FROM_DRAWER, true)"))
        assertTrue(newChat.contains("activity.overridePendingTransition(0, 0)"))
        val reveal = controller.substringAfter("fun revealChat()").substringBefore("fun refresh(")
        // Open instantly, then close with the normal drawer animation once the list is shown.
        assertTrue(reveal.contains("drawer.openDrawer(GravityCompat.START, false)"))
        assertTrue(reveal.contains("refresh { drawer.post { close() } }"))

        val chat = source("ui/activities/ChatActivity.kt")
        val install = chat.indexOf("drawerController = ChatDrawerController.install(")
        val trigger = chat.indexOf("drawerController?.revealChat()")
        assertTrue(install in 0 until trigger)
        assertTrue(chat.contains("intent.removeExtra(ChatDrawerController.EXTRA_REVEAL_FROM_DRAWER)"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
