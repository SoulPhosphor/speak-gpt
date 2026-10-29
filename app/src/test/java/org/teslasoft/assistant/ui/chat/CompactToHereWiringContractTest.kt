/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactToHereWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val adapter by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt")
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun menuItemSitsAboveShareOnLaterAiResponsesOnly() {
        assertTrue(adapter.contains("COMPACT_TO_HERE_FIRST_RESPONSE = 15"))
        val compact = adapter.indexOf("R.string.message_compact_to_here")
        val share = adapter.indexOf("popup.menu.add(0, MENU_MESSAGE_SHARE, 1")
        assertTrue(compact in 0 until share)
        val gate = adapter.substring(adapter.lastIndexOf("if (chatMessage[\"isBot\"] == true &&", compact), compact)
        assertTrue(gate.contains("aiResponseNumber(chatMessage) >= COMPACT_TO_HERE_FIRST_RESPONSE"))
        assertTrue(gate.contains("listener?.isCompactToHereAvailable() == true"))
    }

    @Test
    fun availabilityMatchesTheComposerCompactButton() {
        val available = activity.substring(
            activity.indexOf("override fun isCompactToHereAvailable()"),
            activity.indexOf("override fun onCompactToHere(")
        )
        assertTrue(available.contains("SummarizerController.isConfigured(this)"))
        assertTrue(available.contains("getChatUseSummarizer() != true"))
    }

    @Test
    fun earlierPointRewritesFromTheFirstMessage() {
        val body = activity.substring(
            activity.indexOf("private fun compactThroughMessage("),
            activity.indexOf("/** True when this chat's requests use summarizer transmission")
        )
        assertTrue(body.contains("snapshot.entries.take(count)"))
        assertTrue(body.contains("count <= prefs.getSummarizerFoldedCount()"))
        assertTrue(body.contains("count <= prefs.getManualCompactionBoundary()"))
        assertTrue(body.contains("startManualCompaction("))
    }
}
