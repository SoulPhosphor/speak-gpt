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

class SummarizerErrorsDialogContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun copyHideAndOkaySitOnOneWrappingRow() {
        val dialog = source("src/main/res/layout/dialog_summarizer_errors.xml")
        assertFalse(dialog.contains("dialog_three_actions_cancel_first"))
        assertTrue(dialog.contains("androidx.constraintlayout.helper.widget.Flow"))
        assertTrue(dialog.contains("app:flow_wrapMode=\"chain\""))
        assertTrue(dialog.contains("app:flow_firstHorizontalBias=\"0\""))
        assertTrue(dialog.contains("app:flow_lastHorizontalBias=\"1\""))
        assertTrue(
            dialog.contains(
                "app:constraint_referenced_ids=\"btn_summarizer_errors_copy,btn_summarizer_errors_hide,btn_summarizer_errors_okay\""
            )
        )
    }

    @Test
    fun entriesCarryNoButtonsOfTheirOwn() {
        val entry = source("src/main/res/layout/view_summarizer_error_entry.xml")
        assertFalse(entry.contains("MaterialButton"))
    }

    @Test
    fun hideClearsOnlyTheDialogListAndCopyTakesEveryEntry() {
        val activity = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val body = activity.substring(
            activity.indexOf("private fun showSummarizerErrorsDialog()"),
            activity.indexOf("private fun playSummarizerErrorSignal()")
        )
        assertTrue(body.contains(".renderLog(this, statusText, currentEntries())"))
        assertTrue(body.contains("preferences?.setSummarizerErrors(\"\")"))
        assertFalse(body.contains("ErrorLog.clear"))
        assertFalse(body.contains("removeAt("))
    }
}
