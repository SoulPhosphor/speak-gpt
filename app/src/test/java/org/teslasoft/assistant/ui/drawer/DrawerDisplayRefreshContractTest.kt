/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.drawer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning a chat-list display option on or off (companion images, model
 *  names, bookmarks, start date, memory status, image shape) redraws the list on the next return,
 *  even though no chat in it changed. */
class DrawerDisplayRefreshContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun displayOptionChangesRebindTheList() {
        val adapter = source("src/main/java/org/teslasoft/assistant/ui/drawer/DrawerHierarchyAdapter.kt")
        val options = adapter.substring(adapter.indexOf("private fun displayOptions()"))
        val body = options.substring(0, options.indexOf("\n    )"))
        assertTrue(body.contains("getShowCompanionImagesInChatList()"))
        assertTrue(body.contains("getShowModelNamesInChatList()"))
        assertTrue(body.contains("getShowBookmarkIndicatorInChatList()"))
        assertTrue(body.contains("getShowChatStartDateInChatList()"))
        assertTrue(!body.contains("getHideModelNames()"))
        assertTrue(body.contains("getShowMemoryStatusOnChatList()"))
        assertTrue(adapter.contains("fun refreshDisplayIfChanged(refreshDetails: Boolean = true)"))
        val controller = source("src/main/java/org/teslasoft/assistant/ui/drawer/ChatDrawerController.kt")
        val refresh = controller.substring(controller.indexOf("fun refresh("))
        assertTrue(refresh.indexOf("adapter.refreshDisplayIfChanged(refreshDetails)") in 0 until refresh.indexOf("submitList"))
    }

    /** Opening or closing a folder refreshes the list; each row keeps its
     *  last-read bookmark indicator until the fresh read lands. */
    @Test
    fun aRefreshKeepsLoadedDetailsUntilTheyAreReread() {
        val adapter = source("src/main/java/org/teslasoft/assistant/ui/drawer/DrawerHierarchyAdapter.kt")
        val refresh = adapter.substring(
            adapter.indexOf("fun refreshDisplayIfChanged("),
            adapter.indexOf("private fun needsDetails()")
        )
        val unchanged = refresh.substring(0, refresh.indexOf("if (current == renderedDisplayOptions)"))
        assertTrue(!unchanged.contains("details.clear()"))
        assertTrue(adapter.contains("detailsReadIn[chatId] == generation"))
        assertTrue(adapter.contains("detailsReadIn[chatId] = generation"))
    }
}
