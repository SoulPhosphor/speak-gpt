/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysSpeakPersistenceTest {

    @Test
    fun directTogglePersistsGloballyAcrossChatsAndNewInstances() {
        val global = FakeSharedPreferences()
        val first = Preferences(FakeSharedPreferences(), global, "first")
        val second = Preferences(FakeSharedPreferences(), global, "second")

        first.setNotSilence(true)

        assertTrue(second.getNotSilence())
        assertTrue(Preferences(FakeSharedPreferences(), global, "third").getNotSilence())

        second.setNotSilence(false)
        assertFalse(first.getNotSilence())
    }

    @Test
    fun legacyPerChatValueIsPreservedUntilTheFirstGlobalWrite() {
        val legacy = FakeSharedPreferences()
        legacy.edit().putBoolean("always_speak_mode", true).commit()
        val global = FakeSharedPreferences()
        val preferences = Preferences(legacy, global, "legacy")

        assertTrue(preferences.getNotSilence())

        preferences.setNotSilence(false)
        assertFalse(preferences.getNotSilence())
        assertTrue(global.contains("always_speak_mode"))
    }
}
