/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LastUsedChatLaunchContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun toggleIsOffByDefaultAndOnTheAppearanceScreen() {
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        assertTrue(prefs.contains("getGlobalBoolean(\"open_last_used_chat_at_launch\", false)"))
        val layout = source("src/main/res/layout/activity_app_appearance.xml")
        assertTrue(layout.indexOf("@+id/switch_open_last_used_chat") > layout.indexOf("@string/other_appearance_options_title"))
    }

    @Test
    fun launchStillRestoresTheNewChatSessionAndFallsBackToIt() {
        val gate = source("src/main/java/org/teslasoft/assistant/ui/activities/MainActivity.kt")
        val pending = gate.indexOf("createOrRestoreStartupPendingConversation()")
        val choice = gate.indexOf("lastUsedChat(preferences) ?: StartupDestination.BlankChat(pending)")
        assertTrue(pending in 0 until choice)
        assertTrue(gate.contains("if (!preferences.getOpenLastUsedChatAtLaunch()) return null"))
        assertTrue(gate.contains("if (!ChatStorageHealth.isAuthoritative(list.state)) return null"))
    }

    @Test
    fun aNewChatNotYetSentClearsTheRememberedChat() {
        val chat = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("preferences?.setLastUsedChatId(if (pendingConversation) \"\" else chatId)"))
        assertTrue(chat.contains("pendingConversation = false\n        preferences?.setLastUsedChatId(chatId)"))
    }
}
