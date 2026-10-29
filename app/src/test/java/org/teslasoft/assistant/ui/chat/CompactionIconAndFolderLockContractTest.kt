/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionIconAndFolderLockContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun compactionIconShowsAfterAFailedCompactionAndOpensTheErrors() {
        assertTrue(activity.contains("if (summaryMode != null || compactionErrorsOnly) View.VISIBLE else View.GONE"))
        assertTrue(activity.contains("summaryMode == null && hasErrors && preferences?.getChatUseSummarizer() != true"))
        assertTrue(activity.contains("btnConversationSummary?.setOnClickListener { openCompactionIcon() }"))
        val open = activity.substring(
            activity.indexOf("private fun openCompactionIcon()"),
            activity.indexOf("Summarizer Bookmarks in the chat")
        )
        assertTrue(open.indexOf("showSummarizerErrorsDialog()") in 0 until open.indexOf("openConversationSummary()"))
    }

    @Test
    fun lockFolderLocationIsAnAppAppearanceToggleOffByDefault() {
        val layout = source("src/main/res/layout/activity_app_appearance.xml")
        assertTrue(layout.contains("@+id/switch_chat_list_lock_folders"))
        assertTrue(layout.contains("@string/chat_list_lock_folders_hint"))
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        assertTrue(prefs.contains("getGlobalBoolean(\"chat_list_lock_folders\", false)"))
    }

    @Test
    fun drawerHoldsOnlyTheFoldersRowAtTheTop() {
        val drawer = source("src/main/res/layout/activity_chat_drawer.xml")
        assertTrue(drawer.indexOf("@+id/drawer_folders_pinned") > drawer.indexOf("@+id/drawer_hierarchy"))
        val controller = source("src/main/java/org/teslasoft/assistant/ui/drawer/ChatDrawerController.kt")
        assertTrue(controller.contains("rows.indexOfFirst { it is DrawerRow.FoldersHeader }"))
        assertTrue(controller.contains("getLockFolderLocationInChatList()"))
        assertTrue(controller.contains("holder.bind(rows[index])"))
    }
}
