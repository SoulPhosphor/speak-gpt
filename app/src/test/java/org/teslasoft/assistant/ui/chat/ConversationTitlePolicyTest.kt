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

class ConversationTitlePolicyTest {

    @Test
    fun removesPlainAndMarkdownTitleLabels() {
        assertEquals("Smoke Exposure Housing Request", ConversationTitlePolicy.sanitize("Title: Smoke Exposure Housing Request"))
        assertEquals("Smoke Exposure Housing Request", ConversationTitlePolicy.sanitize("**Title:** Smoke Exposure Housing Request"))
        assertEquals("Smoke Exposure Housing Request", ConversationTitlePolicy.sanitize("## Chat title — Smoke Exposure Housing Request"))
    }

    @Test
    fun choosesLabeledTitleAfterModelPreamble() {
        assertEquals(
            "Keyboard and Chat Positioning",
            ConversationTitlePolicy.sanitize("Sure! Here's a concise title:\nTitle: Keyboard and Chat Positioning")
        )
    }

    @Test
    fun clampsAFullMessageSoItCannotFillTheTitleArea() {
        val title = ConversationTitlePolicy.sanitize(
            "The user wants a detailed explanation of why the keyboard keeps moving around the screen"
        )!!
        assertEquals("The user wants a detailed explanation", title)
        assertTrue(title.length <= ConversationTitlePolicy.MAX_TITLE_CHARS)
        assertTrue(title.split(' ').size <= ConversationTitlePolicy.MAX_TITLE_WORDS)
    }

    @Test
    fun fallbackRemovesRequestScaffolding() {
        assertEquals(
            "fix the bouncing chat keyboard",
            ConversationTitlePolicy.fallbackFromUserMessage("I need you to fix the bouncing chat keyboard please")
        )
    }

    @Test
    fun namingPromptContainsOnlyTheBoundedExcerpt() {
        val excerpt = ConversationTitlePolicy.conversationExcerpt("hello ".repeat(500), "reply ".repeat(500))
        assertTrue(excerpt.startsWith("User: "))
        assertTrue(excerpt.contains(" Assistant: ") || excerpt.contains("\nAssistant: "))
        assertFalse(excerpt.contains("hello ".repeat(400)))
    }
}
