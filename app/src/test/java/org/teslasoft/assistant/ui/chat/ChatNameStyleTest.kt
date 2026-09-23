/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNameStyleTest {

    private val base = ChatNameStyle.Resolved("roboto", 21, bold = true, italic = false)

    @Test
    fun emptyOverrideInheritsEveryField() {
        assertEquals(base, ChatNameStyle.withOverride(base, ChatNameStyle.Override()))
        assertEquals(base, ChatNameStyle.withOverride(base, ChatNameStyle.Override("", 0, "")))
    }

    @Test
    fun eachOverrideFieldAppliesIndependently() {
        val styled = ChatNameStyle.withOverride(base, ChatNameStyle.Override(fontStyle = "italic"))
        assertEquals("roboto", styled.fontId)
        assertEquals(21, styled.sizeSp)
        assertFalse(styled.bold)
        assertTrue(styled.italic)

        val sized = ChatNameStyle.withOverride(base, ChatNameStyle.Override(fontId = "kalnia", sizeSp = 14))
        assertEquals("kalnia", sized.fontId)
        assertEquals(14, sized.sizeSp)
        assertTrue(sized.bold)
    }

    @Test
    fun boldItalicRoundTripsThroughItsStyleId() {
        assertEquals("bold_italic", ChatNameStyle.styleId(bold = true, italic = true))
        val both = ChatNameStyle.withOverride(base, ChatNameStyle.Override(fontStyle = "bold_italic"))
        assertTrue(both.bold && both.italic)
    }

    @Test
    fun fontsListAlphabetically() {
        val names = ChatNameStyle.fontsAlphabetical.map { it.displayName }
        assertEquals(names.sortedBy { it.lowercase() }, names)
        assertEquals("Crafty Girls", names.first())
    }
}
