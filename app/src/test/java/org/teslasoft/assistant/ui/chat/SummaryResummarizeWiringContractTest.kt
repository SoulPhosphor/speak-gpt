/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resummarize-on-change and the Compact progress dialog (owner rulings,
 *  Oct 4 2026). */
class SummaryResummarizeWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val activity by lazy {
        source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
    }

    @Test
    fun editAndRegenerateBothAskBeforeResummarizing() {
        assertTrue(activity.contains("if (!askToResummarizeEditedSection()) summarizerCycle()"))
        assertTrue(activity.contains("withResummarizeDecision(position) { onRetryClick() }"))
        assertTrue(activity.contains("if (regenerateLockKind(position) != null) return"))
    }

    @Test
    fun alwaysResummarizeIsOnByDefaultAndAChatCanRememberItsChoice() {
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        assertTrue(prefs.contains("getGlobalString(\"summarizer_always_resummarize\", \"true\") == \"true\""))
        val perChat = source("src/main/java/org/teslasoft/assistant/preferences/PerChatSettingKeys.kt")
        assertTrue(perChat.contains("\"summary_resummarize_choice\""))
        val layout = source("src/main/res/layout/activity_summarizer_settings.xml")
        assertTrue(layout.indexOf("switch_summarizer_always_resummarize") > layout.indexOf("switch_summarizer_new_chats"))
    }

    @Test
    fun compactCancelKeepsNothingAndHasNoSetting() {
        assertTrue(activity.contains("savePartialOnCancel = false,"))
        assertFalse(source("src/main/res/layout/activity_summarizer_settings.xml").contains("compacting_cancel"))
        assertTrue(activity.contains("if (renderCompactionDialog(state)) return"))
        // After "Compaction complete!", Cancel undoes the compaction; Okay keeps it.
        assertTrue(activity.contains("summarizerController?.discardFinishedCompaction()"))
        assertTrue(activity.contains("summarizerController?.keepFinishedCompaction()"))
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertTrue(controller.contains("withContext(NonCancellable) { restoreRunLock(prefs, startingLock) }"))
    }

    @Test
    fun aCompactFailureShowsTheErrorBoxAndACutOffReplyIsNeverSaved() {
        assertTrue(activity.contains("if (watching) showCompactionFailure(state)"))
        assertTrue(activity.contains(".setNeutralButton(R.string.title_summarizer_settings)"))
        assertTrue(activity.contains(".setNegativeButton(R.string.btn_msg_retry)"))
        val controller = source("src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt")
        assertTrue(controller.contains("if (choice?.finishReason?.value == \"length\") null"))
    }
}
