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

/** With profile images on, the first message's portrait gets the same gap
 *  below the header as a portrait gets below the previous message; the Name
 *  Style saved box reserves no blank lines and uses a normal centered button. */
class FirstMessagePortraitInsetContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    @Test
    fun firstMessageGetsTheBetweenMessageGapWhenPortraitsShow() {
        val inset = source("src/main/java/org/teslasoft/assistant/ui/chat/FirstMessagePortraitInset.kt")
        assertTrue(inset.contains("parent.getChildAdapterPosition(view) == 0 && profileImagesShown()"))
        assertTrue(inset.contains("R.dimen.chat_message_unit_spacing"))
        val chat = source("src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        assertTrue(chat.contains("FirstMessagePortraitInset { preferences?.getShowChatProfileImages() == true }"))
    }

    @Test
    fun nameStyleSavedBoxHasNoReservedLinesAndANormalButton() {
        val themes = source("src/main/res/values/themes.xml")
        val values = themes.substring(themes.indexOf("<style name=\"Widget.App.NameStyle.SavedValues\""))
        assertFalse(values.substring(0, values.indexOf("</style>")).contains("minLines"))
        assertTrue(themes.contains("<style name=\"Widget.App.NameStyle.RestoreOriginal\" parent=\"AppButton.Primary.Inline.Centered\">"))
    }
}
