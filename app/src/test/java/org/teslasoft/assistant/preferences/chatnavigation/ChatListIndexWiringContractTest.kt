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
 * Every way a chat-list detail can change updates the database; a restore
 * prepares the chats it restored before it finishes; drawing a row opens no
 * chat file; backups and the memory export are untouched.
 */
class ChatListIndexWiringContractTest {

    private fun file(relative: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        return File(roots.first { it.isDirectory }, relative).readText()
    }

    private fun source(relative: String) = file("java/org/teslasoft/assistant/$relative")

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

    @Test fun everyWriterOfAShownSettingUpdatesTheDetails() {
        val preferences = source("preferences/Preferences.kt")
        listOf(
            "fun setModel(", "fun setPersonaId(", "fun setChatExcludedFromMemory(",
            // Archive This Chat and new-chat Quick Settings write the exclusion directly.
            "fun setChatArchiveEnabled(", "fun initializeNewChatQuickSettings("
        ).forEach { assertTrue("$it must update the chat list", body(preferences, it).contains("chatListSettingChanged()")) }
        assertTrue(body(preferences, "private fun chatListSettingChanged(").contains("ChatListIndex.settingsChanged("))
        // Every direct write of the exclusion is one of the hooked functions above.
        assertTrue(Regex("\"memory_excluded\"").findAll(preferences).count() == 4)
    }

    @Test fun conversationChangesCreationAndDeletionUpdateTheDetails() {
        val chats = source("preferences/ChatPreferences.kt")
        assertTrue(body(chats, "fun saveChatHistory(").contains("ChatListIndex.contentChanged(context, chatId)"))
        assertTrue(body(chats, "fun commitPendingConversation(").contains("ChatListIndex.chatCreated(context, chatId)"))
        assertTrue(body(chats, "internal fun cleanupDeletedChatData(").contains("ChatListIndex.chatDeleted(context, chatId)"))
        val usage = source("usage/UsageLog.kt")
        val store = usage.substring(usage.indexOf("object UsageLogStore"))
        listOf("fun append(", "fun putRequest(").forEach {
            val text = body(store.substring(store.indexOf(it)), it)
            assertTrue(text.contains("UsageCategory.CHAT"))
            assertTrue(text.contains("prefs.chatUsageRecorded()"))
        }
    }

    @Test fun aRestorePreparesItsChatsBeforeItFinishes() {
        val rebase = body(
            source("preferences/backup/ChatSetReplacementCoordinator.kt"), "fun onAuthoritativeChatSetReplaced("
        )
        val prepared = rebase.indexOf("ChatListIndex.prepareRestored(appContext, restoredChatIds)")
        assertTrue(prepared >= 0)
        assertTrue(prepared < rebase.indexOf("return RebaseResult(searchOk, catalogOk)"))
    }

    @Test fun existingChatsArePreparedAfterAnUpdateAndAtStart() {
        val manifest = file("AndroidManifest.xml")
        assertTrue(manifest.contains(".preferences.chatnavigation.AppUpdatedReceiver"))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(source("ui/drawer/ChatDrawerController.kt").contains("ChatListIndex.prepareAllInBackground(activity)"))
    }

    @Test fun listsAreShownOnlyWithEveryChatReady() {
        val controller = source("ui/drawer/ChatDrawerController.kt")
        assertTrue(body(controller, "fun readyNavigation(").contains("ChatListIndex.ensureReady(context, chats)"))
        assertTrue(controller.contains("readyNavigation(activity, repository)"))
        assertTrue(source("ui/activities/FoldersActivity.kt").contains("ChatDrawerController.readyNavigation("))
        assertTrue(body(source("ui/adapters/SearchResultAdapter.kt"), "private fun readRows(").contains("ChatListIndex.ensureReady("))
    }

    @Test fun drawingARowOpensNoChatFile() {
        val binder = source("ui/drawer/FlatChatIdentityBinder.kt")
        val bind = body(binder, "fun bind(chatId: String")
        assertFalse(bind.contains("Preferences.getPreferences(activity, chatId)"))
        assertFalse(bind.contains("getChatById"))
        assertFalse(bind.contains("PersonaPreferences"))
        assertFalse(binder.contains("settingsOnly"))
        listOf("ui/drawer/DrawerHierarchyAdapter.kt", "ui/adapters/SearchResultAdapter.kt").forEach {
            assertFalse("$it still reads conversations per row", source(it).contains("ChatListDetailsReader"))
        }
    }

    @Test fun memoryStatusIsStillReadInOneBulkPass() {
        val drawer = source("ui/drawer/DrawerHierarchyAdapter.kt")
        assertTrue(drawer.contains("MemoryStore.getInstance(activity).chatReviewStates()"))
        val bind = body(source("ui/drawer/FlatChatIdentityBinder.kt"), "fun bind(chatId: String")
        assertTrue(bind.contains("if (index.memoryExcluded) \"excluded\" else memoryStates[chatId]"))
    }

    @Test fun backupsAndTheMemoryExportAreUntouched() {
        listOf(
            "preferences/backup/portable/ChatLogicalSerializer.kt",
            "preferences/backup/portable/ChatsArtifactWriter.kt",
            "preferences/memory/MemoryExporter.kt"
        ).forEach { assertFalse("$it must not change for the chat-list details", source(it).contains("ChatListIndex")) }
        assertTrue(source("preferences/backup/portable/ChatLogicalSerializer.kt")
            .contains("private val EXCLUDED_SETTINGS_KEYS = setOf(\"api_key\")"))
    }
}
