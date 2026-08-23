/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the requested action-bar and composer geometry against regressions. */
class ChatActionSurfaceSourceContractTest {

    @Test
    fun activeMemoriesIsTheConditionalFarLeftAssistantAction() {
        val layout = source("main/res/layout/view_assistant_bot_message.xml")
        assertOrdered(layout, "@+id/btn_active_memories", "@+id/btn_details")
        assertTrue(view(layout, "btn_active_memories").contains("android:visibility=\"gone\""))
    }

    @Test
    fun everyAssistantActionUsesTheSameFixedSpacingAndNoWeights() {
        val layout = source("main/res/layout/view_assistant_bot_message.xml")
        assertOrdered(layout, "@+id/btn_retry", "@+id/btn_copy", "@+id/btn_edit", "@+id/btn_more")
        val actionIds = listOf(
            "btn_active_memories",
            "btn_details",
            "reasoning_indicator",
            "btn_speak",
            "btn_share",
            "btn_retry",
            "btn_copy",
            "btn_edit",
            "btn_more"
        )
        for (id in actionIds) {
            val action = view(layout, id)
            assertTrue("$id must remain 32dp wide", action.contains("android:layout_width=\"32dp\""))
            assertTrue("$id must have the shared 8dp gap", action.contains("android:layout_marginLeft=\"8dp\""))
            assertTrue("$id must not redistribute the row", !action.contains("android:layout_weight"))
        }
        assertTrue(view(layout, "btn_share").contains("android:visibility=\"gone\""))
        assertTrue(view(layout, "btn_edit").contains("android:visibility=\"gone\""))
    }

    @Test
    fun expandIsBesideMicrophoneAndCollapseHasNoTonalBackground() {
        val layout = source("main/res/layout/activity_chat.xml")
        assertOrdered(layout, "@+id/btn_expand_content", "@+id/btn_micro")
        assertTrue(view(layout, "btn_expand_content").contains("android:background=\"@color/transparent\""))
        assertTrue(view(layout, "btn_collapse_content").contains("android:background=\"@color/transparent\""))

        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")
        assertTrue(composer.contains("if (!expanded && active)"))
    }

    @Test
    fun composerPromotesAfterTheFocusTapAndKeepsTheImeRequest() {
        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")
        assertTrue(composer.contains("if (movingFocusedEditor) return@setOnFocusChangeListener"))
        assertTrue(composer.contains("messageInput.post {"))
        assertTrue(composer.contains("applyMode()\n                        messageInput.requestFocus()"))
        assertTrue(composer.contains("?.show(WindowInsetsCompat.Type.ime())"))
    }

    @Test
    fun requestedSettingsRowsUseTheirExactGoogleIcons() {
        val layout = source("main/res/layout/activity_settings.xml")
        val aiSystem = sectionStartingAt(layout, "@+id/tile_ai_system_settings", 600)
        val appearance = sectionStartingAt(layout, "@+id/tile_appearance", 600)

        assertTrue(aiSystem.contains("android:src=\"@drawable/ic_desktop_cloud\""))
        assertTrue(appearance.contains("android:src=\"@drawable/ic_forum\""))
    }

    @Test
    fun processingRingReplacesInsteadOfOverlaysConversationIcon() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        assertTrue(activity.contains("btnSend?.visibility = if (visible) View.INVISIBLE else View.VISIBLE"))
    }

    private fun view(xml: String, id: String): String {
        val marker = "android:id=\"@+id/$id\""
        val start = xml.indexOf(marker)
        check(start >= 0) { "Missing $id" }
        val tagStart = xml.lastIndexOf('<', start)
        val tagEnd = xml.indexOf("/>", start)
        check(tagStart >= 0 && tagEnd >= 0) { "Could not isolate $id" }
        return xml.substring(tagStart, tagEnd + 2)
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        val positions = markers.map(source::indexOf)
        assertTrue("Missing marker in $markers", positions.all { it >= 0 })
        assertTrue("Markers are out of order: $markers", positions.zipWithNext().all { it.first < it.second })
    }

    private fun sectionStartingAt(source: String, marker: String, length: Int): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "Missing $marker" }
        return source.substring(start, (start + length).coerceAtMost(source.length))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File(System.getProperty("user.dir"), "src/$relative"),
            File(System.getProperty("user.dir"), "app/src/$relative")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relative")
    }
}
