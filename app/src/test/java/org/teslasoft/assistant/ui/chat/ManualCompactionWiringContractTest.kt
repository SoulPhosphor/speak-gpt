/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun createImageIsLastInThePlusMenuAndQuickSettingsSitsBesidePlus() {
        val layout = source("src/main/res/layout/activity_chat.xml")
        assertTrue(layout.indexOf("@+id/btn_chat_tools") > layout.indexOf("@+id/btn_attach"))
        // Compact to Here in the AI message menu stays; no composer Compact.
        assertFalse(layout.contains("@+id/action_compact"))
        // Create Image moved from the gear menu to the end of the plus menu
        // (owner ruling, Nov 2 2026); the gear menu is gone.
        val plusMenu = layout.substringAfter("android:id=\"@+id/vision_action_selector\"")
        assertTrue(plusMenu.indexOf("@+id/action_create_image") > plusMenu.indexOf("@+id/action_document"))
        assertFalse(layout.contains("tool_action_selector"))
        assertFalse(activity.contains("val compactAvailable ="))
        assertTrue(activity.contains("val imageAvailable = imageGeneratorConfigured()"))
        assertTrue(activity.contains("btnToolCreateImage?.visibility = if (imageAvailable) View.VISIBLE else View.GONE"))
        // The composer button is Quick Settings; the header no longer has it.
        val composerButton = layout.substringAfter("android:id=\"@+id/btn_chat_tools\"").substringBefore("/>")
        assertTrue(composerButton.contains("@drawable/ic_history_edu"))
        assertFalse(layout.contains("@+id/btn_quick_settings"))
        assertTrue(activity.contains("btnChatTools?.setOnClickListener {\n            visionActions?.visibility = View.GONE\n            openSummoningCircle()"))
    }

    @Test
    fun manualCompactionUsesAttachmentBlindSnapshotAndActivatesTransmission() {
        assertTrue(activity.contains(".summarizerConversation(storedCanonical)"))
        assertTrue(activity.contains("controller.runManualCompaction("))
        assertTrue(activity.contains("getManualCompactionBoundary()"))
        assertTrue(activity.contains("ManualCompactionStorageGuard"))
        assertTrue(activity.contains("snapshot.entries.drop(alreadyFolded)"))

        val controller = source(
            "src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt"
        )
        val manual = controller.substring(
            controller.indexOf("private suspend fun compactSnapshot"),
            controller.indexOf("private suspend fun buildOneSection")
        )
        assertTrue(manual.contains("prefs.commitManualCompaction("))
        assertFalse(manual.contains("prefs.commitSummarizerFoldIn("))
    }

    @Test
    fun successfullyCondensedRepliesBecomeImmutableHistory() {
        val controller = source(
            "src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt"
        )
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        val preferences = source(
            "src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"
        )

        assertTrue(controller.contains("advanceSummaryRegenerationLockBoundary("))
        // A finished compaction saves its regeneration lock in the same
        // commit as its text (compaction no longer saves batch by batch).
        assertTrue(controller.contains("regenerationLock = CompactionVersions.furthestCount(versions)"))
        assertTrue(preferences.contains("ensureCondensedRegenerationLockMigration()"))
        assertTrue(adapter.contains("R.drawable.ic_rule_settings"))
        assertTrue(adapter.contains("showRegenerateUnavailablePopup(anchor, lockKind)"))
        assertTrue(adapter.contains("promote.visibility = View.GONE"))
        assertTrue(adapter.contains("position + 1 == summaryRegenerationLockBoundary"))
        assertTrue(source("src/main/res/values/strings.xml").contains("──────── Summarized ────────"))
        assertTrue(activity.contains("if (condensedRegenerationLockKind(position) == CondensedRegenerationLock.Kind.COMPACTION) return"))
    }

    @Test
    fun gearImageDraftCanOverrideOnlyTheGlobalImagineToggle() {
        assertTrue(activity.contains("explicitImagineDraft = true"))
        assertTrue(activity.contains("preferences!!.getImagineCommandGlobal() || explicitlyArmedImagine"))
        assertTrue(activity.contains("preferences?.getImagineCommandGlobal() == true || explicitImagineDraft"))
        assertTrue(activity.contains("!ImagineCommand.isImagineAttempt"))
    }

    @Test
    fun newComposerPresentationLivesInSharedStyles() {
        val layout = source("src/main/res/layout/activity_chat.xml")
        val themes = source("src/main/res/values/themes.xml")
        val dimensions = source("src/main/res/values/dimens.xml")
        assertTrue(layout.contains("@style/Widget.App.Chat.ComposerAction"))
        assertTrue(layout.contains("@style/Widget.App.Chat.ActionMenu.Row"))
        assertTrue(layout.contains("@style/Widget.App.Chat.ActionMenu.Icon"))
        assertTrue(layout.contains("@style/Widget.App.Chat.ActionMenu.Label"))
        assertTrue(themes.contains("Widget.App.Chat.CompactionMarker"))
        assertTrue(dimensions.contains("chat_action_menu_blur_radius"))
        assertTrue(activity.contains("R.dimen.chat_action_menu_blur_radius"))
        assertFalse(activity.contains("val radius = 16f"))
    }

    @Test
    fun reviewScreenSwitchesBetweenCondensedAndFullTransmission() {
        val review = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ConversationSummaryActivity.kt"
        )
        assertTrue(review.contains("setUseSummarizedConversationProjection(false)"))
        assertTrue(review.contains("setUseSummarizedConversationProjection(true)"))
        assertTrue(review.contains("setSummarizerCatchUpPending(true)"))
        assertTrue(review.contains("OperationKind.SUMMARIZING"))
        assertTrue(review.contains("SummarizerControllerRegistry.cancel(chatId)"))
    }

    @Test
    fun compactIsOfferedOnlyWhileTheChatDoesNotUseTheSummarizer() {
        // The gear's Compact is gone (owner ruling, Nov 2 2026); Compact to
        // Here keeps the same rule.
        val available = activity.substring(
            activity.indexOf("override fun isCompactToHereAvailable()"),
            activity.indexOf("override fun onCompactToHere(")
        )
        assertTrue(available.contains(".isConfigured(this) &&"))
        assertTrue(available.contains("preferences?.getChatUseSummarizer() != true"))
        assertTrue(activity.contains("R.string.compact_unavailable_with_summarizer"))
    }

    @Test
    fun recreatingTheSummaryScreenNeverPutsAnotherTabsTextIntoTheVersionInUse() {
        val screen = source("src/main/java/org/teslasoft/assistant/ui/activities/ConversationSummaryActivity.kt")
        assertTrue(screen.contains("field?.isSaveEnabled = false"))
        assertTrue(screen.contains("encodeDrafts(panel.drafts()) else activeText()"))
    }
}
