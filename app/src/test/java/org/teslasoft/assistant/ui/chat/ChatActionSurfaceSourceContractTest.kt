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
        val transcript = source("main/java/org/teslasoft/assistant/ui/chat/ChatTranscriptRecyclerView.kt")
        val layout = source("main/res/layout/activity_chat.xml")

        // The keyboard resizes the chat viewport exactly as the composer does,
        // so it has to report before it takes or gives back that space.
        // Narrowing this back to composer-only resizes is what puts the
        // conversation back to jumping when the keyboard opens and closes.
        assertTrue(composer.contains("onBottomInsetChanging?.invoke(isKeyboardOpen, retreating)"))
        assertTrue(activity.contains("keyboardInput?.onBottomInsetChanging = { keyboardOpen, retreating ->"))
        assertTrue(activity.contains("before = ::captureTranscriptAnchor"))
        assertTrue(layout.contains("org.teslasoft.assistant.ui.chat.ChatTranscriptRecyclerView"))

        // Capture happens with the old size. The absolute anchor is armed from
        // onSizeChanged, before RecyclerView lays out the new height; it must not
        // be posted for later, where a composer promotion and the next IME frame
        // can overwrite or overtake it.
        assertTrue(transcript.contains("findLastVisibleItemPosition()"))
        assertTrue(transcript.contains("resizeAnchorTopFromBottom = anchor.top - height"))
        assertTrue(transcript.contains("override fun onSizeChanged"))
        assertTrue(!transcript.contains("post {"))

        // An absolute position, not a shift by the height difference: a growing
        // viewport is already partly corrected by the transcript itself, and a
        // blind shift would double that and hide the newest message.
        assertTrue(transcript.contains("h + resizeAnchorTopFromBottom"))
        assertTrue(transcript.contains("scrollToPositionWithOffset("))

        // An open keyboard locks the conversation in place. A reply arriving or
        // growing must never move it out from under the user, so nothing here
        // may make an incoming message win over the held position.
        assertTrue(composer.contains("isKeyboardOpen = imeBottom > 0"))
        assertTrue(activity.contains("keyboardInput?.isKeyboardOpen == true && !imeClosingForSend"))
        assertTrue(activity.contains("if (!disableAutoScroll && !keyboardHolding)"))

        // The single exception: the keyboard closing because the user hit Send.
        // That close is the user asking for the reply, so the transcript follows
        // it. The flag is consumed by the next keyboard change, so reopening the
        // keyboard mid-reply locks the conversation again.
        assertTrue(activity.contains("imeClosingForSend = true\n        composerSurface?.dismissImeForSend()"))
        // The exception ends when the keyboard is actually gone, not on the
        // first inset frame: a close can take several, and the early ones still
        // report the keyboard as present. Consuming it eagerly hands the rest of
        // the close back to the hold and strands the reply off screen.
        assertTrue(composer.contains("val retreating = imeBottom < lastImeBottom"))
        assertTrue(activity.contains("if (imeClosingForSend && (retreating || !keyboardOpen))"))
        assertTrue(activity.contains("if (!keyboardOpen) imeClosingForSend = false"))

        // Opening the keyboard mid-reply ends automatic follow for the rest of
        // that reply. Closing the keyboard again must not resume it, so this
        // uses the same latch as a touch rather than a keyboard-open check.
        assertTrue(activity.contains("if (keyboardOpen && replyIsStreaming()) disableAutoScroll = true"))

        assertTrue(!activity.contains("WindowInsetsAnimationCompat.Callback"))
        assertTrue(activity.contains("composerSurface?.dismissImeForSend()"))
        assertTrue(activity.contains("composerSurface?.resetAfterSend()"))
    }

    @Test
    fun visibleReadbackStopControlsCannotBeDisabledByBusyWork() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")

        // Android does not dispatch click/touch listeners to disabled views.
        // Every busy path therefore uses the stop-aware helper instead of raw
        // false assignments, including the hidden auto-title work that overlaps
        // typed-turn readback.
        assertTrue(activity.contains("private fun disableTurnControlsUnlessTheyAreStops()"))
        assertTrue(activity.contains(
            "btnMicro?.isEnabled = transcriptionInProgress ||"
        ))
        assertTrue(activity.contains("btnSend?.isEnabled = isHandsFreeEngaged()"))
        assertTrue(!activity.contains("btnMicro?.isEnabled = false"))
        assertTrue(!activity.contains("btnSend?.isEnabled = false"))

        // The state painter also enables the control immediately, closing the
        // short gap between readback start and the generation-finally cleanup.
        assertTrue(activity.contains(
            "private fun micReadbackStop() {\n        btnMicro?.apply {\n            isEnabled = true"
        ))
        assertTrue(activity.contains(
            "private fun micHandsFreeActive(listening: Boolean) {\n        btnSend?.apply {\n            isEnabled = true"
        ))
    }

    @Test
    fun normalChatOpenPositionsTheTallLastMessageAtItsActualEnd() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val transcript = source("main/java/org/teslasoft/assistant/ui/chat/ChatTranscriptRecyclerView.kt")

        assertTrue(activity.contains("chat?.scrollToTranscriptEnd()"))
        assertTrue(transcript.contains("fun scrollToTranscriptEnd()"))
        assertTrue(transcript.contains("lastView.bottom - (height - paddingBottom)"))
        assertTrue(transcript.contains("if (overflow != 0) scrollBy(0, overflow)"))
    }

    @Test
    fun transcriptionImmediatelyOwnsAnEnabledStopControl() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")

        assertTrue(activity.contains("private var transcriptionInProgress = false"))
        assertTrue(activity.contains("private fun micTranscribing()"))
        assertTrue(activity.contains("messageInput?.hint = getString(R.string.hint_transcribing)"))
        assertTrue(activity.contains("transcriptionInProgress = true\n        micTranscribing()"))
        assertTrue(activity.contains("btnMicro?.isEnabled = transcriptionInProgress ||"))
        assertTrue(activity.contains("transcriptionInProgress = false\n            micIdle()"))
    }

    @Test
    fun composerPromotesAfterTheFocusTapAndKeepsTheImeRequest() {
        val composer = source("main/java/org/teslasoft/assistant/ui/chat/ChatComposerLayout.kt")
        assertTrue(composer.contains("if (movingFocusedEditor) return@setOnFocusChangeListener"))
        assertTrue(composer.contains("messageInput.post {"))
        assertTrue(composer.contains("applyMode()\n                        messageInput.requestFocus()"))
        assertTrue(composer.contains("?.show(WindowInsetsCompat.Type.ime())"))
    }

    // chat-keyboard-behavior.md rule 8 (owner ruling, Aug 29 2026): a finished
    // AI turn must never bring the keyboard up on its own, in any mode. Focusing
    // the composer runs the focus listener above, which shows the IME, so no
    // completion path may focus the message box. Every turn — chat or image,
    // success or failure, and the hidden auto-title request that can follow —
    // ends by hiding the progress ring; none of those points focuses the
    // composer. The keyboard opens only when the user taps the composer. The
    // keyboard opening after a turn is a regression, not desired behavior.
    @Test
    fun aFinishedTurnDoesNotOpenTheKeyboard() {
        val activity = source("main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")

        // Every turn end hides the progress ring. No such point may be followed
        // by focusing the message box, which is what would open the keyboard.
        var index = activity.indexOf("setGenerationProgressVisible(false)")
        var checked = 0
        while (index >= 0) {
            val window = activity.substring(index, (index + 80).coerceAtMost(activity.length))
            assertTrue(
                "A finished turn must not focus the composer (rule 8): $window",
                !window.contains("requestFocus")
            )
            checked++
            index = activity.indexOf("setGenerationProgressVisible(false)", index + 1)
        }
        assertTrue("Expected the turn-end hide-progress sites to still exist", checked >= 5)
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
