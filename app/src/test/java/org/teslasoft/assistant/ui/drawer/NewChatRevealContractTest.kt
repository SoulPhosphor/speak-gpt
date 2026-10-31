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
        val reveal = controller.substringAfter("fun revealChat(").substringBefore("fun refresh(")
        // Open instantly, then close with the normal drawer animation once the open drawer has been drawn.
        assertTrue(reveal.contains("drawer.openDrawer(GravityCompat.START, false)"))
        assertTrue(reveal.contains("drawer.doOnPreDraw {"))
        assertTrue(reveal.contains("close()"))
        // The drawer opens already filled in from the list last shown.
        assertTrue(reveal.contains("if (shown != null) showProjection(shown) { pullBack() } else refresh { pullBack() }"))

        val chat = source("ui/activities/ChatActivity.kt")
        val install = chat.indexOf("drawerController = ChatDrawerController.install(")
        val trigger = chat.indexOf("drawerController?.revealChat(")
        assertTrue(install in 0 until trigger)
        assertTrue(chat.contains("intent.removeExtra(ChatDrawerController.EXTRA_REVEAL_FROM_DRAWER)"))
    }

    @Test fun aChatOpenedFromTheDrawerKeepsThePreviousScreenUntilItsDrawerIsDrawn() {
        val controller = source("ui/drawer/ChatDrawerController.kt")
        val open = controller.substringAfter("private fun openConversationFromDrawer(").substringBefore("companion object")
        // Opened on top of the current screen instead of clearing the app's screens first.
        assertTrue(open.contains("intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)\n"))
        assertTrue(open.contains("replaced = WeakReference(activity)"))
        assertTrue(open.contains("if (opening) return"))
        // The previous screen is closed only once the new drawer starts to move.
        val retire = controller.substringAfter("private inner class RetireOnMove").substringBefore("fun refresh(")
        assertTrue(retire.contains("retireReplaced(activity)"))
        val revealBody = controller.substringAfter("fun revealChat(").substringBefore("private inner class RetireOnMove")
        assertTrue(revealBody.indexOf("drawer.addDrawerListener(RetireOnMove(onCovered))") in 0 until revealBody.indexOf("close()"))

        val chat = source("ui/activities/ChatActivity.kt")
        // Nothing of the new screen dims the previous one while it loads.
        assertTrue(chat.contains("window.setBackgroundDrawableResource(android.R.color.transparent)"))
        // A chat that cannot open still closes the screen it replaced.
        val startup = chat.substringAfter("val startupResult = startupAttempt.getOrElse").substringBefore("initializeChatUi(")
        assertTrue(startup.indexOf("ChatDrawerController.retireReplaced(this)") in 0 until
            startup.indexOf("startActivity("))
    }

    @Test fun openingAChatSkipsWorkThatIsAlreadyDone() {
        val chat = source("ui/activities/ChatActivity.kt")
        val startup = chat.substringAfter("private fun prepareChatStartup()").substringBefore("private fun initializeChatUi(")
        // The usage log is copied from the messages only the first time; the full
        // repair stays on the Usage screen and before every append.
        assertTrue(startup.contains("UsageLogStore.seedOnce(preparedPreferences, historyResult.messages)"))
        assertTrue(!startup.contains("UsageLogStore.seed(preparedPreferences"))
        // The whole chat list is not read to save a start date once every chat has one.
        assertTrue(startup.indexOf("if (!org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex.startDatesFilled(this))") in 0 until
            startup.indexOf("chatPreferences.rememberChatStartDate("))

        // Every chat's start date is filled in once, again after a restore.
        val index = source("preferences/chatnavigation/ChatListIndex.kt")
        assertTrue(index.substringAfter("fun prepareAll(context: Context)").substringBefore("private fun maintenance(")
            .contains("fillStartDates(context)"))
        val restored = index.substringAfter("fun prepareRestored(").substringBefore("fun contentChanged(")
        assertTrue(restored.contains("putBoolean(KEY_START_DATES_FILLED, false)"))
        assertTrue(restored.contains("fillStartDates(context)"))

        // Every way of removing messages saves their usage records to the log first.
        listOf(
            "private fun deleteMessageRange(" to "messages.subList(currentPosition, end).clear()",
            "private fun deleteBulkSelection(" to "messages.removeAt(pos - p)",
            "private fun truncateAfter(" to "messages.subList(index + 1, end).clear()"
        ).forEach { (function, removal) ->
            val body = chat.substringAfter(function)
            assertTrue(function, body.indexOf("reconcileUsageBeforeRemoving()") in 0 until body.indexOf(removal))
        }

        val usage = source("usage/UsageLog.kt")
        val seedOnce = usage.substringAfter("fun seedOnce(").substringBefore("fun append(")
        assertTrue(seedOnce.contains("if (current.seeded) current else seed(prefs, messages, nowMs)"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
