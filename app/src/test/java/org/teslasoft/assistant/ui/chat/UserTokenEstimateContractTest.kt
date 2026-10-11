/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both message roles estimate only their own visible text. Provider request
 * accounting belongs to Usage & Cost, not the number beside a message. */
class UserTokenEstimateContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun userMessagesShowAnEstimateOnlyWhenEnabled() {
        val adapter = source("src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt")
        assertTrue(adapter.contains("No service reports one visible message's own tokens"))
        assertTrue(adapter.contains("if (isBot) tokenCountLabel(chatMessage) == null"))
        assertTrue(adapter.contains("else preferences.getShowUserTokens()"))
        assertTrue(adapter.contains("KEY_MESSAGE_TOKEN_ESTIMATE"))
        assertTrue(adapter.contains("R.string.chat_user_token_estimate"))
        val bindStart = adapter.indexOf("open fun bind(")
        val boundId = adapter.indexOf("boundMessageId =", bindStart)
        val compactMetadata = adapter.indexOf("updateMessageMeta(display", bindStart)
        assertTrue(boundId in bindStart until compactMetadata)
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"chat_user_token_estimate\">About %1\$s Tokens</string>"))
        assertTrue(strings.contains("<string name=\"chat_token_count\">%1\$s Tokens</string>"))
        assertTrue(strings.contains("<string name=\"chat_reasoning_token_count\">%1\$s Reasoning Tokens</string>"))
        assertTrue(strings.contains("<string name=\"appearance_token_usage\">Show AI Response Tokens</string>"))
        assertTrue(strings.contains("<string name=\"appearance_user_tokens\">Show User Tokens</string>"))
        assertTrue(strings.contains("<string name=\"appearance_user_tokens_hint\">Shows the estimated tokens for each user submitted text message.</string>"))
        val appearance = source("src/main/res/layout/activity_chat_settings.xml")
        assertTrue(appearance.indexOf("switch_token_usage") < appearance.indexOf("switch_user_tokens"))
    }
}
