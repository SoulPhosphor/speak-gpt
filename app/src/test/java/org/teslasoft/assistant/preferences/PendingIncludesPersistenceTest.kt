/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIncludesPersistenceTest {
    @Test fun synchronousPendingIncludeSaveReportsCommitFailure() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("pending_includes", "old").commit()
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        editor.commit()
                        return false
                    }
                }
            }
        }
        val preferences = Preferences(failing, FakeSharedPreferences(), "chat")

        assertFalse(preferences.setPendingIncludes("[]", synchronous = true))
        assertTrue(preferences.getPendingIncludes() == "old")
    }

    @Test fun synchronousPendingIncludeSaveReportsSuccess() {
        val backing = FakeSharedPreferences()
        val preferences = Preferences(backing, FakeSharedPreferences(), "chat")

        assertTrue(preferences.setPendingIncludes("[]", synchronous = true))
        assertTrue(preferences.getPendingIncludes() == "[]")
    }
}
