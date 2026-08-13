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

/** Structural guards for the single adaptable Phase 2 chat presentation. */
class ChatPresentationContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val messageLayouts = listOf(
        "src/main/res/layout/view_assistant_bot_message.xml",
        "src/main/res/layout/view_assistant_user_message.xml"
    )

    @Test
    fun classicRendererAndChatReportActionAreRetired() {
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        val preferences = source(
            "src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"
        )
        val reportSheet = source(
            "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/ReportAIContentBottomSheet.kt"
        )

        assertFalse(adapter.contains("TYPE_CLASSIC"))
        assertFalse(adapter.contains("view_message"))
        assertFalse(adapter.contains("getLayout()"))
        assertFalse(adapter.contains("btnReport"))
        assertFalse(adapter.contains("ReportAIContentBottomSheet"))
        assertFalse(preferences.contains("fun getLayout()"))
        assertFalse(preferences.contains("fun setLayout("))
        assertFalse(reportSheet.contains("reportFromChat"))
        assertFalse(
            listOf(
                File("src/main/res/layout/view_message.xml"),
                File("app/src/main/res/layout/view_message.xml")
            ).any { it.exists() }
        )
        for (path in messageLayouts) {
            assertFalse(path, source(path).contains("btn_report"))
        }
    }

    @Test
    fun appearanceControlsAdaptTheCurrentPresentation() {
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        assertTrue(adapter.contains("getShowChatProfileImages()"))
        assertTrue(adapter.contains("getShowChatNames()"))
        assertTrue(adapter.contains("getShowAiBubble()"))
        assertTrue(adapter.contains("getShowUserBubble()"))
        assertTrue(adapter.contains("ChatNameStyle.apply("))
        assertTrue(adapter.contains("setCompanionPresentation("))
    }

    @Test
    fun layoutsUseApprovedGeometryResourcesAndKeepExistingActions() {
        val dimens = source("src/main/res/values/dimens.xml")
        assertTrue(dimens.contains("name=\"chat_message_speaker_inset\">27dp"))
        assertTrue(dimens.contains("name=\"chat_message_user_left_inset\">26dp"))
        assertTrue(dimens.contains("name=\"chat_message_content_padding\">14dp"))
        assertTrue(dimens.contains("name=\"chat_message_unit_spacing\">53dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_size\">76dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_text_clearance\">40dp"))

        for (path in messageLayouts) {
            val xml = source(path)
            assertTrue(path, xml.contains("@dimen/chat_message_speaker_inset"))
            assertTrue(path, xml.contains("@dimen/chat_message_content_padding"))
            assertTrue(path, xml.contains("@dimen/chat_message_unit_spacing"))
            assertTrue(path, xml.contains("@dimen/chat_portrait_size"))

            val actions = listOf("btn_speak", "btn_share", "btn_retry", "btn_copy", "btn_edit")
            val positions = actions.map { xml.indexOf("@+id/$it") }
            assertTrue("$path is missing an existing action", positions.all { it >= 0 })
            assertTrue("$path changed the existing action order", positions == positions.sorted())
        }
    }
}
