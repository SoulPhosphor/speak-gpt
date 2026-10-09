/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** App start opens the blank chat directly: no task-reset transition, no
 *  dimmed loading window, and no loading spinner. */
class LaunchHandoffContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun launcherGateHandsOffWithoutResettingItsOwnTask() {
        val gate = source("src/main/java/org/teslasoft/assistant/ui/activities/MainActivity.kt")
        val blankChat = gate.substring(gate.indexOf("is StartupDestination.BlankChat ->"))
        assertTrue(blankChat.contains("putExtra(ChatActivity.EXTRA_OPENED_AT_LAUNCH, true)"))
        assertTrue(blankChat.contains("if (isTaskRoot)"))
        assertTrue(blankChat.contains(
            "removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)"
        ))
        assertTrue(blankChat.contains("Intent.FLAG_ACTIVITY_NO_ANIMATION"))
        assertTrue(blankChat.contains("overridePendingTransition(0, 0)"))
    }

    @Test
    fun launchChatShowsItsSurfaceAndSkipsTheLoadingSpinner() {
        val chat = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val onCreate = chat.substring(chat.indexOf("override fun onCreate"))
        val surface = onCreate.indexOf("R.drawable.expandable_window_background_24")
        val worker = onCreate.indexOf("Thread {")
        assertTrue(surface in 0 until worker)
        assertTrue(chat.contains(
            "threadLoader?.visibility = if (openedAtLaunch()) View.GONE else View.VISIBLE"
        ))
        assertTrue(chat.contains("if (threadLoader?.visibility == View.VISIBLE) Handler("))
        val content = chat.indexOf("setContentView(R.layout.activity_chat)")
        assertTrue(chat.indexOf("window.setBackgroundDrawableResource(R.color.shadow)", content) > content)
    }
}
