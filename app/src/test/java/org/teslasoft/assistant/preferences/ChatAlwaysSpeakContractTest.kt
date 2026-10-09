/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Always Speak Responses in Quick Settings is per chat: an unset chat follows
 *  the Settings default, the chat's own choice never changes that default, a
 *  new chat never inherits it, and replies are read aloud by the chat's value. */
class ChatAlwaysSpeakContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun chatValueFollowsTheDefaultUntilSetAndStaysWithTheChat() {
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        val getter = prefs.substring(prefs.indexOf("fun getChatAlwaysSpeak()"))
        assertTrue(getter.substring(0, getter.indexOf("\n    }")).contains("else -> getNotSilence()"))
        val setter = prefs.substring(prefs.indexOf("fun setChatAlwaysSpeak("))
        assertFalse(setter.substring(0, setter.indexOf("\n    }")).contains("always_speak_mode"))
        val coordinator = source("src/main/java/org/teslasoft/assistant/conversation/NewConversationCoordinator.kt")
        assertFalse(coordinator.contains("ChatAlwaysSpeak"))
        val chat = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("preferences!!.getChatAlwaysSpeak() || handsFree"))
    }

    @Test
    fun quickSettingsShowsItBetweenCharacterAndProviderBlocks() {
        val sheet = source("src/main/res/layout/fragment_quick_settings.xml")
        val character = sheet.indexOf("@layout/view_quick_settings_character_block")
        val voice = sheet.indexOf("@layout/view_quick_settings_voice_block")
        val provider = sheet.indexOf("@layout/view_quick_settings_provider_block")
        assertTrue(character in 0 until voice && voice < provider)
        val block = source("src/main/res/layout/view_quick_settings_voice_block.xml")
        assertTrue(block.contains("Widget.App.QuickSettings.Segment.Standalone"))
        assertTrue(block.contains("@string/always_speak_responses"))
    }
}
