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
        // Open instantly, then start the normal close animation on the first frame.
        assertTrue(reveal.contains("drawer.openDrawer(GravityCompat.START, false)"))
        assertTrue(reveal.contains("drawer.post { close() }"))
        assertTrue(!reveal.contains("refresh {"))

        val chat = source("ui/activities/ChatActivity.kt")
        val onCreate = chat.substring(chat.indexOf("override fun onCreate"))
        val shell = onCreate.indexOf("initializeChatShell()")
        val worker = onCreate.indexOf("Thread {")
        assertTrue(shell in 0 until worker)
        val initializeShell = chat.substringAfter("private fun initializeChatShell()")
            .substringBefore("private fun prepareChatStartup()")
        val install = initializeShell.indexOf("drawerController = ChatDrawerController.install(")
        val trigger = initializeShell.indexOf("drawerController?.revealChat()")
        assertTrue(install in 0 until trigger)
        assertTrue(chat.contains("intent.removeExtra(ChatDrawerController.EXTRA_REVEAL_FROM_DRAWER)"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
