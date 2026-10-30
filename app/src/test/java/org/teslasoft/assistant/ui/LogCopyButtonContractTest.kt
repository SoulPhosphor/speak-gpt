/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCopyButtonContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun eachLogEntryHasARightAlignedSharedCopyButton() {
        val entry = source("src/main/res/layout/view_log_entry.xml")
        assertTrue(entry.contains("style=\"@style/AppButton.Primary.Inline.End\""))
        assertTrue(entry.contains("@string/btn_copy"))
        val themes = source("src/main/res/values/themes.xml")
        assertTrue(themes.contains("<style name=\"AppButton.Primary.Inline.End\" parent=\"AppButton.Primary.Inline\">"))
    }

    @Test
    fun showCopyButtonOnLogsDefaultsOnAndGatesTheButtons() {
        val prefs = source("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        assertTrue(prefs.contains("getGlobalBoolean(\"logs_show_copy_buttons\", true)"))
        val appearance = source("src/main/res/layout/activity_app_appearance.xml")
        assertTrue(appearance.contains("@string/folder_options_title"))
        assertTrue(appearance.contains("@string/other_appearance_options_title"))
        assertTrue(appearance.contains("@+id/switch_logs_show_copy_buttons"))
        val logs = source("src/main/java/org/teslasoft/assistant/ui/activities/LogsActivity.kt")
        assertTrue(logs.contains("preferences?.getShowLogCopyButtons()"))
    }
}
