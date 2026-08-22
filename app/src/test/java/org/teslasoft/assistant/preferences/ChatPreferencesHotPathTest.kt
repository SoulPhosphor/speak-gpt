/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPreferencesHotPathTest {

    @Test
    fun metadataMutationsNeverHydrateEveryChatHistory() {
        val source = chatPreferencesSource().readText()

        assertTrue(
            source.contains(
                "getChatListResult(context, includeFirstMessage = false).chats"
            )
        )
        assertFalse(
            "A chat-list mutation is calling the display reader and will parse every history",
            Regex("""(?:val|var) list = getChatList\(context\)""").containsMatchIn(source)
        )

        val timestampMutation = source.substringAfter(
            "private fun putMetadataToChatById"
        ).take(1_200)
        assertTrue(timestampMutation.contains("getChatMetadataList(context)"))
    }

    private fun chatPreferencesSource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/preferences/ChatPreferences.kt"
        return listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate ChatPreferences.kt")
    }
}
