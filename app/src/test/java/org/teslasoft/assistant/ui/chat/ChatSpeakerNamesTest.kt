/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSpeakerNamesTest {

    @Test
    fun roleplayNameWinsOverGlamourWhenEnabled() {
        assertEquals("Rook", ChatSpeakerNames.activeUserName("Rook", "Vesper", "Sam", roleplayWins = true))
    }

    @Test
    fun glamourNameWinsOverRoleplayWhenDisabled() {
        assertEquals("Vesper", ChatSpeakerNames.activeUserName("Rook", "Vesper", "Sam", roleplayWins = false))
    }

    @Test
    fun singleIdentityAppliesRegardlessOfToggle() {
        assertEquals("Rook", ChatSpeakerNames.activeUserName("Rook", null, "Sam", roleplayWins = false))
        assertEquals("Vesper", ChatSpeakerNames.activeUserName(" ", "Vesper", "Sam", roleplayWins = true))
    }

    @Test
    fun defaultUsernameAppliesWithNoIdentity() {
        assertEquals("Sam", ChatSpeakerNames.activeUserName(null, "", " Sam ", roleplayWins = true))
    }

    @Test
    fun nothingToStampWhenEverythingIsBlank() {
        assertNull(ChatSpeakerNames.activeUserName(null, null, "  ", roleplayWins = true))
    }
}
