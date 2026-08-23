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
    fun gearAndMenuAreCapabilityDriven() {
        val layout = source("src/main/res/layout/activity_chat.xml")
        assertTrue(layout.indexOf("@+id/btn_chat_tools") > layout.indexOf("@+id/btn_attach"))
        assertTrue(layout.contains("@+id/action_compact"))
        assertTrue(layout.contains("@+id/action_create_image"))
        assertTrue(activity.contains("val compactAvailable ="))
        assertTrue(activity.contains("val imageAvailable = imageGeneratorConfigured()"))
        assertTrue(activity.contains("if (compactAvailable || imageAvailable)"))
    }

    @Test
    fun manualCompactionUsesReferenceOnlySnapshotAndActivatesTransmission() {
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
            controller.indexOf("private suspend fun foldOneBatch")
        )
        assertTrue(manual.contains("prefs.commitManualCompaction("))
        assertFalse(manual.contains("prefs.commitSummarizerFoldIn("))
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
    fun projectionSwitchClosesTheSummaryWindowInBothDirections() {
        val summaryView = activity.substring(
            activity.indexOf("private fun showSummaryView()"),
            activity.indexOf("private fun showProjectionStatus")
        )
        assertTrue(summaryView.contains("showProjectionStatus(enableCondensed)\n            dialog.dismiss()"))
        assertTrue(summaryView.contains("setSummarizerCatchUpPending(true)"))
        assertTrue(summaryView.contains("OperationKind.SUMMARIZING"))
        assertTrue(summaryView.contains("summarizerController?.cancel()"))
        assertEquals(1, Regex("dialog\\.show\\(\\)").findAll(summaryView).count())
    }
}
