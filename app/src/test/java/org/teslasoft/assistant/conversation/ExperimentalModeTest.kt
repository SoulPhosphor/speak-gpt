/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.includes.CanonicalConversationMessage

class ExperimentalModeTest {

    private val history = listOf(
        CanonicalConversationMessage(isBot = false, text = "first"),
        CanonicalConversationMessage(isBot = true, text = "reply"),
        CanonicalConversationMessage(isBot = false, text = "current")
    )

    @Test
    fun offSendsTheWholeConversation() {
        assertEquals(history, ExperimentalMode.requestConversation(history, enabled = false))
    }

    @Test
    fun onSendsOnlyTheCurrentMessage() {
        assertEquals(
            listOf(history.last()),
            ExperimentalMode.requestConversation(history, enabled = true)
        )
    }

    @Test
    fun onKeepsOnlyTheNewestUserEntryOfAnyList() {
        val roles = listOf("user", "assistant", "user", "assistant")
        assertEquals(
            listOf(2),
            ExperimentalMode.requestEntries(roles.indices.toList(), enabled = true) {
                roles[it] == "user"
            }
        )
        assertEquals(
            roles.indices.toList(),
            ExperimentalMode.requestEntries(roles.indices.toList(), enabled = false) {
                roles[it] == "user"
            }
        )
    }

    @Test
    fun onWithNoUserMessageSendsNothing() {
        val onlyReply = listOf(CanonicalConversationMessage(isBot = true, text = "reply"))
        assertTrue(ExperimentalMode.requestConversation(onlyReply, enabled = true).isEmpty())
    }
}
