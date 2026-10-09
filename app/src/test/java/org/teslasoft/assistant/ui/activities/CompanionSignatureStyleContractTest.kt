/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edit Companion shows the Chat Signature Style preview and a row into Name
 *  Style, has no font/size controls of its own, and its linked-lorebook cards
 *  use the shared app surface rather than the device accent. */
class CompanionSignatureStyleContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private val editor get() = source("src/main/java/org/teslasoft/assistant/ui/activities/EditPersonaActivity.kt")

    @Test
    fun signatureSectionPreviewsAndOpensNameStyle() {
        val layout = source("src/main/res/layout/activity_edit_persona.xml")
        assertTrue(layout.contains("Widget.App.CompanionEditor.SignaturePreview"))
        assertTrue(layout.contains("Widget.App.CompanionEditor.SignatureRow"))
        assertFalse(layout.contains("field_chat_name_font"))
        assertFalse(layout.contains("field_chat_name_size"))
        assertTrue(editor.contains("NameStyleActivity.companionIntent(this, personaId)"))
        assertTrue(editor.contains("ChatNameStyle.ai(Preferences.getPreferences(this, \"\"), stored)"))
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"companion_chat_name_style\">Chat Signature Style</string>"))
        assertTrue(strings.contains("<string name=\"companion_change_chat_name_style\">Change Chat Name Style</string>"))
    }

    @Test
    fun savingACompanionKeepsItsNameStyleOverride() {
        assertFalse(editor.contains("EXTRA_CHAT_NAME_"))
        val build = editor.substring(editor.indexOf("private fun buildPersonaObject"))
        assertTrue(build.contains("persona.chatNameFontId = stored.chatNameFontId"))
        assertTrue(build.contains("persona.chatNameSizeSp = stored.chatNameSizeSp"))
        assertTrue(build.contains("persona.chatNameFontStyle = stored.chatNameFontStyle"))
    }

    @Test
    fun linkedLoreBookCardsUseSharedSurfaceAndBareIcons() {
        val row = source("src/main/res/layout/view_persona_lorebook_row.xml")
        assertTrue(row.contains("Widget.App.CompanionEditor.LoreBookCard"))
        assertFalse(row.contains("colorSecondaryContainer"))
        assertFalse(row.contains("btn_accent_tonal"))
        assertFalse(row.contains("android:textColor"))
        val themes = source("src/main/res/values/themes.xml")
        val card = themes.substring(themes.indexOf("<style name=\"Widget.App.CompanionEditor.LoreBookCard\""))
        assertTrue(card.substring(0, card.indexOf("</style>")).contains("@drawable/bg_quick_settings_segment_standalone"))
        assertTrue(themes.contains("<style name=\"Widget.App.CompanionEditor.LoreBookAction\" parent=\"Widget.App.QuickTile.EditButton\">"))
    }
}
