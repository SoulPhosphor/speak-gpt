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
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
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
        val preferences = source(
            "src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"
        )
        val nameStyle = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/ChatNameStyle.kt"
        )
        val appearance = source(
            "src/main/res/layout/activity_appearance.xml"
        )
        assertTrue(adapter.contains("getShowChatProfileImages()"))
        assertTrue(adapter.contains("getShowChatNames()"))
        assertTrue(adapter.contains("getShowAiBubble()"))
        assertTrue(adapter.contains("getShowUserBubble()"))
        assertTrue(adapter.contains("ChatNameStyle.apply("))
        assertTrue(adapter.contains("setCompanionPresentation("))
        assertTrue(preferences.contains("getGlobalBoolean(\"chat_bold_user_name\", false)"))
        assertTrue(preferences.contains("getGlobalBoolean(\"chat_bold_ai_name\", false)"))
        assertTrue(nameStyle.contains("if (style.bold) Typeface.BOLD else Typeface.NORMAL"))
        assertTrue(appearance.contains("@+id/switch_bold_user_name"))
        assertTrue(appearance.contains("@+id/switch_bold_companion_name"))
        for (path in messageLayouts) {
            assertFalse(path, source(path).contains("android:textStyle=\"bold\""))
        }
    }

    @Test
    fun thinkingAndComposerTogglesUseCentralThemeReadyStyles() {
        val themes = source("src/main/res/values/themes.xml")
        val assistantLayout = source("src/main/res/layout/view_assistant_bot_message.xml")
        val chatLayout = source("src/main/res/layout/activity_chat.xml")
        val chatActivity = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        )
        val expandIcon = source("src/main/res/drawable/ic_expand_content.xml")
        val collapseIcon = source("src/main/res/drawable/ic_collapse_content.xml")

        listOf("Container", "Header", "Label", "Chevron", "Body").forEach { part ->
            assertTrue(
                "Thinking $part style is missing",
                themes.contains("name=\"Widget.App.Chat.Thinking.$part\"")
            )
            assertTrue(
                "Thinking $part is not using its shared style",
                assistantLayout.contains("style=\"@style/Widget.App.Chat.Thinking.$part\"")
            )
        }
        assertTrue(
            themes.contains("name=\"Widget.App.Chat.ComposerContentToggle\"")
        )
        assertTrue(
            chatLayout.split("style=\"@style/Widget.App.Chat.ComposerContentToggle\"").size - 1 == 2
        )
        assertFalse(chatActivity.contains("btnExpandContent?.background"))
        assertFalse(chatActivity.contains("btnCollapseContent?.background"))
        assertFalse(expandIcon.contains("android:tint="))
        assertFalse(collapseIcon.contains("android:tint="))
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        assertTrue(adapter.contains("reasoningChevron?.setColorFilter(foreground)"))
        assertTrue(adapter.contains("reasoningLabel?.setTextColor(foreground)"))
        assertTrue(adapter.contains("reasoningText?.setTextColor(foreground)"))
        assertTrue(adapter.contains("R.drawable.ic_chevron_up"))
        assertTrue(adapter.contains("R.drawable.ic_chevron_down"))
        assertFalse(adapter.contains("reasoningChevron?.rotation"))
    }

    @Test
    fun modelAndTokenMetadataWrapsAgainstItsMeasuredMessageWidth() {
        val layout = source("src/main/res/layout/view_assistant_bot_message.xml")
        val metadataView = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/MessageMetadataView.kt"
        )
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )

        assertTrue(layout.contains("org.teslasoft.assistant.ui.chat.MessageMetadataView"))
        assertTrue(metadataView.contains("Layout.getDesiredWidth"))
        assertTrue(metadataView.contains("MeasureSpec.getSize(widthMeasureSpec)"))
        assertTrue(metadataView.contains("\"\$model\\n\$tokens\""))
        assertTrue(adapter.contains("meta.setMetadata(modelPart, tokenPart)"))
        assertFalse(adapter.contains("availableMetaWidthPx"))
        assertTrue(adapter.contains("setPortraitSideMargin("))
        assertTrue(adapter.contains("measuredNameClearancePx("))
        assertTrue(adapter.contains("messageMeta?.lineHeight ?: 0"))
    }

    @Test
    fun portraitFlowUsesMeasuredBoundsAcrossThinkingAndReply() {
        val layout = source("src/main/res/layout/view_assistant_bot_message.xml")
        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        val portraitAwareText = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/PortraitAwareMessageTextView.kt"
        )

        assertTrue(
            viewTag(layout, "reasoning_text")
                .contains("org.teslasoft.assistant.ui.chat.PortraitAwareMessageTextView")
        )
        assertTrue(adapter.contains("ui.offsetDescendantRectToMyCoords(view, bounds)"))
        assertTrue(adapter.contains("reasoningText?.requestPortraitGeometryUpdate()"))
        assertTrue(adapter.contains("message.requestPortraitGeometryUpdate()"))
        assertTrue(adapter.contains("updateMeasuredNameInset(portraitBounds)"))
        assertTrue(adapter.contains("portraitIsOnLeft(portraitBounds)"))
        assertTrue(adapter.contains("ui.layoutDirection != View.LAYOUT_DIRECTION_RTL"))
        assertTrue(portraitAwareText.contains("PortraitExclusionGeometry.horizontalExclusion("))
        assertTrue(portraitAwareText.contains("horizontal.portraitOnLeft"))
        assertTrue(portraitAwareText.contains("portrait?.layoutParams?.width"))
        assertFalse(
            portraitAwareText.contains(
                "dimen(R.dimen.chat_portrait_top_offset) + dimen(R.dimen.chat_portrait_size)"
            )
        )
    }

    private fun viewTag(layout: String, id: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        if (idAt < 0) return ""
        val start = layout.lastIndexOf('<', idAt)
        val end = layout.indexOf('>', idAt)
        return if (start >= 0 && end >= 0) layout.substring(start, end + 1) else ""
    }

    @Test
    fun layoutsUseApprovedGeometryResourcesAndKeepExistingActions() {
        val dimens = source("src/main/res/values/dimens.xml")
        assertTrue(dimens.contains("name=\"chat_message_speaker_inset\">25dp"))
        assertTrue(dimens.contains("name=\"chat_message_ai_right_inset\">28dp"))
        assertTrue(dimens.contains("name=\"chat_message_user_left_inset\">49dp"))
        assertTrue(dimens.contains("name=\"chat_message_content_padding\">14dp"))
        assertTrue(dimens.contains("name=\"chat_message_leading_spacing\">22dp"))
        assertTrue(dimens.contains("name=\"chat_message_unit_spacing\">22dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_size\">96dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_edge_inset\">17dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_top_offset\">-20dp"))
        assertTrue(dimens.contains("name=\"chat_name_portrait_edge_inset\">127dp"))
        assertTrue(dimens.contains("name=\"chat_name_portrait_top\">10dp"))
        assertTrue(dimens.contains("name=\"chat_name_body_gap\">9dp"))
        assertTrue(dimens.contains("name=\"chat_portrait_text_clearance\">0dp"))

        for (path in messageLayouts) {
            val xml = source(path)
            assertTrue(path, xml.contains("@dimen/chat_message_speaker_inset"))
            assertTrue(path, xml.contains("@dimen/chat_message_content_padding"))
            assertTrue(path, xml.contains("android:paddingTop=\"@dimen/chat_message_leading_spacing\""))
            assertTrue(path, xml.contains("@dimen/chat_message_unit_spacing"))
            assertTrue(path, xml.contains("@dimen/chat_portrait_size"))
            assertTrue(
                path,
                xml.contains("android:layout_marginTop=\"@dimen/chat_portrait_top_offset\"")
            )
            assertTrue(path, xml.contains("PortraitAwareMessageTextView"))

            val actions = listOf("btn_speak", "btn_share", "btn_retry", "btn_copy", "btn_edit")
            val positions = actions.map { xml.indexOf("@+id/$it") }
            assertTrue("$path is missing an existing action", positions.all { it >= 0 })
            assertTrue("$path changed the existing action order", positions == positions.sorted())
        }
    }

    @Test
    fun tunerAndAndroidShareTheApprovedPortraitVerticalAnchor() {
        val tuner = source("chat-geometry-tuner.html")
        assertTrue(tuner.contains("--portrait-y:-20px"))
        assertTrue(tuner.contains("id=\"portraitY\" type=\"range\" min=\"-65\" max=\"20\" value=\"-20\""))
        assertTrue(tuner.contains("portraitY:-20"))

        val portraitAwareText = source(
            "src/main/java/org/teslasoft/assistant/ui/chat/PortraitAwareMessageTextView.kt"
        )
        assertTrue(portraitAwareText.contains("boundsInRow(row, portrait)"))
        assertTrue(portraitAwareText.contains("portraitBounds.bottom"))
        assertTrue(portraitAwareText.contains("contentBounds.top"))
    }

    @Test
    fun portraitsAndNamesKeepTheTunersLayeringAndForegrounds() {
        for (path in messageLayouts) {
            val xml = source(path)
            val bubble = xml.indexOf("android:id=\"@+id/bubble_bg\"")
            val name = xml.indexOf("android:id=\"@+id/username\"")
            val portrait = xml.indexOf("android:id=\"@+id/icon\"")
            assertTrue("$path is missing a presentation layer", listOf(bubble, name, portrait).all { it >= 0 })
            assertTrue("$path must draw bubble, then name, then portrait", bubble < name && name < portrait)
        }

        val userLayout = source("src/main/res/layout/view_assistant_user_message.xml")
        val userNameStart = userLayout.indexOf("android:id=\"@+id/username\"")
        val userNameEnd = userLayout.indexOf("/>", userNameStart)
        assertTrue(
            "The user name must contrast with the primary-colored user bubble",
            userLayout.substring(userNameStart, userNameEnd).contains("android:textColor=\"?attr/colorSurface\"")
        )

        val adapter = source(
            "src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt"
        )
        assertTrue(adapter.split("username.setTextColor(foreground)").size - 1 >= 2)
        assertTrue(adapter.contains("private fun useFullPortraitSlot()"))
        assertTrue(adapter.contains("icon.setPadding(0, 0, 0, 0)"))
        assertTrue(adapter.contains("private fun restorePortraitGlyphSlot()"))
        assertTrue(Regex("useFullPortraitSlot\\(\\)").findAll(adapter).count() >= 5)
        assertTrue(Regex("restorePortraitGlyphSlot\\(\\)").findAll(adapter).count() >= 4)
    }
}
