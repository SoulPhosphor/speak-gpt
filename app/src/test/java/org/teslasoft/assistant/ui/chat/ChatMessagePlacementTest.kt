/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagePlacementTest {

    @Test
    fun assistantRepliesAlwaysUseLogicalStart() {
        assertTrue(
            ChatMessagePlacement.usesLogicalStart(isBot = true, staggeredResponses = true)
        )
        assertTrue(
            ChatMessagePlacement.usesLogicalStart(isBot = true, staggeredResponses = false)
        )
    }

    @Test
    fun userPromptsFollowTheStaggeredResponsesSetting() {
        assertFalse(
            ChatMessagePlacement.usesLogicalStart(isBot = false, staggeredResponses = true)
        )
        assertTrue(
            ChatMessagePlacement.usesLogicalStart(isBot = false, staggeredResponses = false)
        )
    }
}
