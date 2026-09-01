/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.teslasoft.assistant.util.Hash
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

    @Test
    fun storedIdsSurviveTitleChangesIncludingLegacyHashIds() {
        for (id in listOf("existing-uuid", Hash.hash("Original"))) {
            val entry = hashMapOf("id" to id, "name" to "Original")
            for (title in listOf("Renamed", "Again", "Original")) {
                entry["name"] = title
                assertEquals(id, ChatPreferences.storedChatId(entry))
            }
        }
        // Compatibility read only; no ID is written to an older malformed row.
        val noId = hashMapOf("name" to "Legacy")
        assertEquals(Hash.hash("Legacy"), ChatPreferences.storedChatId(noId))
        assertFalse(noId.containsKey("id"))
    }

    @Test
    fun renameChangesOnlyTitleAndAutoNamesFollowCurrentTitles() {
        val source = chatPreferencesSource().readText()
        val rename = source.substringAfter("fun editChat(")
            .substringBefore("private fun securePrefsFileAccess")
        assertTrue(rename.contains("if (!entry.containsKey(\"id\")) newId = Hash.hash(chatName)"))
        assertFalse(rename.contains("entry[\"id\"] ="))
        assertTrue(rename.contains("entry[\"name\"] = chatName"))
        assertTrue(rename.contains("val oldId = chatId"))
        assertTrue(rename.contains("val newId = oldId"))
        assertTrue(rename.contains("if (oldId == newId) {"))
        val autoName = source.substringAfter("fun getAvailableChatIdForAutoname(")
            .substringBefore("fun commitPendingConversation(")
        assertTrue(autoName.contains("return nextAutonameNumber(list)"))
        assertFalse(autoName.contains("Hash.hash("))
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
