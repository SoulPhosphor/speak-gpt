/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way a value shown on a chat row can change keeps that row's stored
 * details up to date, drawing a row opens no chat file, and the stored
 * details are derived display copies that never enter an export.
 */
class ChatListIndexWiringContractTest {

    private fun source(relative: String): String {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return File(roots.first { it.isDirectory }, "org/teslasoft/assistant/$relative").readText()
    }

    /** The text of the function starting at [signature], through its closing brace. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var depth = 0
        var index = text.indexOf('{', start)
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, index + 1)
            }
            index++
        }
        throw AssertionError("unterminated $signature")
    }

    @Test fun everyWriterOfAShownSettingUpdatesTheRow() {
        val preferences = source("preferences/Preferences.kt")
        listOf(
            "fun setModel(", "fun setPersonaId(", "fun setChatExcludedFromMemory(",
            // Archive This Chat and new-chat Quick Settings write the exclusion directly.
            "fun setChatArchiveEnabled(", "fun initializeNewChatQuickSettings("
        ).forEach { assertTrue("$it must update the chat list", body(preferences, it).contains("chatListSettingChanged()")) }
        assertTrue(body(preferences, "private fun chatListSettingChanged(").contains("ChatListIndex.settingsChanged("))
        // Every direct write of the exclusion is one of the hooked functions above.
        val directWrites = Regex("\"memory_excluded\"").findAll(preferences).count()
        assertTrue(directWrites == 4)
    }

    @Test fun conversationSavesAndChatUsageUpdateTheRow() {
        val chats = source("preferences/ChatPreferences.kt")
        assertTrue(body(chats, "fun saveChatHistory(").contains("ChatListIndex.contentChanged(context, chatId)"))
        assertTrue(body(chats, "fun commitPendingConversation(").contains("ChatListIndex.fillNow(context, chatId)"))
        val usage = source("usage/UsageLog.kt")
        val store = usage.substring(usage.indexOf("object UsageLogStore"))
        listOf("fun append(", "fun putRequest(").forEach {
            val text = body(store.substring(store.indexOf(it)), it)
            assertTrue(text.contains("UsageCategory.CHAT"))
            assertTrue(text.contains("prefs.chatUsageRecorded()"))
        }
    }

    @Test fun drawingARowOpensNoChatFile() {
        val binder = body(source("ui/drawer/FlatChatIdentityBinder.kt"), "fun bind(chatId: String")
        assertFalse(binder.contains("Preferences.getPreferences(activity, chatId)"))
        assertFalse(binder.contains("getChatById"))
        assertFalse(binder.contains("PersonaPreferences"))
        listOf("ui/drawer/DrawerHierarchyAdapter.kt", "ui/adapters/SearchResultAdapter.kt").forEach {
            assertFalse("$it still reads conversations per row", source(it).contains("ChatListDetailsReader"))
        }
    }

    /** A row not filled yet looks exactly as it did before stored details existed. */
    @Test fun unfilledRowsLookTheSameAsBefore() {
        val binder = source("ui/drawer/FlatChatIdentityBinder.kt")
        assertTrue(body(binder, "fun bind(chatId: String").contains("val index = stored ?: settingsOnly(chatId)"))
        val settingsOnly = body(binder, "private fun settingsOnly(")
        listOf("getModel()", "getPersonaId()", "isChatExcludedFromMemory()").forEach { assertTrue(settingsOnly.contains(it)) }
        // Search showed a model label only after the chat was read; the drawer showed the selected model at once.
        assertTrue(source("ui/adapters/SearchResultAdapter.kt").contains("showSelectedModelUntilFilled = false"))
    }

    @Test fun memoryStatusIsStillReadInOneBulkPass() {
        val drawer = source("ui/drawer/DrawerHierarchyAdapter.kt")
        assertTrue(drawer.contains("MemoryStore.getInstance(activity).chatReviewStates()"))
        val binder = body(source("ui/drawer/FlatChatIdentityBinder.kt"), "fun bind(chatId: String")
        assertTrue(binder.contains("if (index.memoryExcluded) \"excluded\" else memoryStates[chatId]"))
    }

    @Test fun unfilledRowsAreQueuedAndRowsOnScreenGoFirst() {
        val controller = source("ui/drawer/ChatDrawerController.kt")
        assertTrue(controller.contains("ChatListIndex.fillMissing("))
        assertTrue(controller.contains("it.listIndex == null"))
        assertTrue(source("ui/drawer/DrawerHierarchyAdapter.kt")
            .contains("if (row.value.listIndex == null) ChatListIndex.fillNow(activity, row.value.id)"))
    }

    @Test fun displayCopiesStayOutOfEveryExport() {
        val skip = "key !in ChatListIndex.KEYS"
        listOf(
            "preferences/backup/portable/ChatLogicalSerializer.kt",
            "preferences/backup/portable/ChatsArtifactWriter.kt",
            "preferences/memory/MemoryExporter.kt"
        ).forEach { assertTrue("$it must leave out the display copies", source(it).contains(skip)) }
        // Real per-chat settings are still exported apart from the API key.
        assertTrue(source("preferences/backup/portable/ChatLogicalSerializer.kt")
            .contains("private val EXCLUDED_SETTINGS_KEYS = setOf(\"api_key\")"))
    }
}
