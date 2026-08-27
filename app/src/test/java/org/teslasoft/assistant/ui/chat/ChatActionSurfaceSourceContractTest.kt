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
    fun expandIsBesideMicrophoneAndContentTogglesUseTheBareSharedStyle() {
        val layout = source("main/res/layout/activity_chat.xml")
        assertOrdered(layout, "@+id/composer_controls_spacer", "@+id/btn_expand_content")
        assertOrdered(layout, "@+id/btn_expand_content", "@+id/btn_micro")
        val sharedStyle = "style=\"@style/Widget.App.Chat.ComposerContentToggle\""
        assertTrue(view(layout, "btn_expand_content").contains(sharedStyle))
        assertTrue(view(layout, "btn_collapse_content").contains(sharedStyle))

        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")
        assertTrue(composer.contains("if (!expanded && active)"))
        assertTrue(composer.contains("controlsSpacer.visibility = View.VISIBLE"))
        assertTrue(composer.contains("controlsSpacer.visibility = View.GONE"))
    }

    @Test
    fun everyViewportResizePinsTranscriptWithoutFightingTheImeConstraint() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")

        // The keyboard resizes the chat viewport exactly as the composer does,
        // so it has to report before it takes or gives back that space.
        // Narrowing this back to composer-only resizes is what puts the
        // conversation back to jumping when the keyboard opens and closes.
        assertTrue(composer.contains("onBottomInsetChanging?.invoke()"))
        assertTrue(activity.contains("keyboardInput?.onBottomInsetChanging = ::captureTranscriptAnchor"))
        assertTrue(activity.contains("before = ::captureTranscriptAnchor"))

        // Captured while the viewport still has its old size, restored on the
        // viewport's own resize so it cannot land before the resize it is
        // compensating for.
        assertTrue(activity.contains("findLastVisibleItemPosition()"))
        assertTrue(activity.contains("transcriptAnchorTopFromBottom = anchor.top - recycler.height"))
        assertTrue(activity.contains("if (bottom - top != oldBottom - oldTop) restoreTranscriptAnchorAfterResize()"))

        // An absolute position, not a shift by the height difference: a growing
        // viewport is already partly corrected by the transcript itself, and a
        // blind shift would double that and hide the newest message.
        assertTrue(activity.contains("recycler.height + transcriptAnchorTopFromBottom"))
        assertTrue(activity.contains("scrollToPositionWithOffset("))

        // An open keyboard locks the conversation in place. A reply arriving or
        // growing must never move it out from under the user, so nothing here
        // may make an incoming message win over the held position.
        assertTrue(composer.contains("isKeyboardOpen = imeBottom > 0"))
        assertTrue(activity.contains("!disableAutoScroll && keyboardInput?.isKeyboardOpen != true"))

        assertTrue(!activity.contains("WindowInsetsAnimationCompat.Callback"))
        assertTrue(activity.contains("composerSurface?.dismissImeForSend()"))
        assertTrue(activity.contains("composerSurface?.resetAfterSend()"))
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
    fun topAudioControlDefaultsOnAndUsesTheExistingSpeakCallback() {
        val settings = source("main/res/layout/activity_chat_settings.xml")
        val preferences = source("main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        val assistantLayout = source("main/res/layout/view_assistant_bot_message.xml")
        val adapter = source("main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt")

        assertTrue(settings.contains("@+id/switch_top_positioned_audio_control"))
        assertTrue(settings.contains("@string/chat_settings_top_positioned_audio_control"))
        assertTrue(preferences.contains("getGlobalBoolean(\"chat_top_positioned_audio_control\", true)"))
        assertTrue(assistantLayout.contains("@+id/btn_speak_top"))
        assertTrue(view(assistantLayout, "btn_speak_top").contains("app:layout_constraintStart_toEndOf=\"@+id/username\""))
        assertTrue(adapter.contains("preferences.getTopPositionedAudioControl()"))
        assertTrue(adapter.contains("listener?.onSpeakClick("))
    }

    @Test
    fun responseVersionNavigatorWrapsAsOneRightAlignedUnitOnlyWhenNeeded() {
        val layout = source("main/res/layout/view_assistant_bot_message.xml")
        val responsive = source(
            "main/java/org/teslasoft/assistant/ui/chat/ResponsiveMessageActionsLayout.kt"
        )

        assertTrue(layout.contains("org.teslasoft.assistant.ui.chat.ResponsiveMessageActionsLayout"))
        assertTrue(responsive.contains("primaryWidth + versionWidth > availableWidth"))
        assertTrue(responsive.contains("child === versionNavigator"))
        assertTrue(responsive.contains("width - paddingRight - outerWidth(versionNavigator)"))
    }

    @Test
    fun composerDefersReparentingUntilHierarchyRestoreFinishes() {
        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")
        val restore = composer.substring(
            composer.indexOf("override fun onRestoreInstanceState"),
            composer.indexOf("private class SavedState")
        )

        assertTrue(restore.contains("post {"))
        assertTrue(restore.indexOf("post {") < restore.indexOf("applyMode()"))
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
