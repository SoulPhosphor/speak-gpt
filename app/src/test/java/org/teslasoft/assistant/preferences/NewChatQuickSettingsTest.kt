/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatQuickSettingsTest {

    private fun chat(global: FakeSharedPreferences, store: FakeSharedPreferences = FakeSharedPreferences()) =
        Preferences(store, global, "chat") to store

    @Test
    fun memoryIsResolvedOnceForEachNewChatAndThenPinned() {
        val global = FakeSharedPreferences()
        val defaults = Preferences(FakeSharedPreferences(), global, "defaults")

        defaults.setMemoryEngine("associative")
        defaults.setDefaultMemoryEnabled(true)
        val (first, firstStore) = chat(global)
        first.initializeNewChatQuickSettings()

        defaults.setDefaultMemoryEnabled(false)
        val (second, secondStore) = chat(global)
        second.initializeNewChatQuickSettings()

        defaults.setDefaultMemoryEnabled(true)
        defaults.setMemoryEngine("lorebooks")
        val (third, thirdStore) = chat(global)
        third.initializeNewChatQuickSettings()

        assertTrue(first.getChatMemoryEnabled())
        assertFalse(second.getChatMemoryEnabled())
        assertFalse(third.getChatMemoryEnabled())
        assertTrue(firstStore.contains("memory_enabled"))
        assertTrue(secondStore.contains("memory_enabled"))
        assertTrue(thirdStore.contains("memory_enabled"))

        defaults.setMemoryEngine("both")
        defaults.setDefaultMemoryEnabled(true)
        assertTrue(first.getChatMemoryEnabled())
        assertFalse(second.getChatMemoryEnabled())
        assertFalse(third.getChatMemoryEnabled())
    }

    @Test
    fun modelRulesUseTheGlobalValueAtCreationWithoutChangingOlderChats() {
        val global = FakeSharedPreferences()
        val defaults = Preferences(FakeSharedPreferences(), global, "defaults")

        defaults.setAutoApplyModelRules(true)
        val (first, firstStore) = chat(global)
        first.initializeNewChatQuickSettings()

        defaults.setAutoApplyModelRules(false)
        val (second, secondStore) = chat(global)
        second.initializeNewChatQuickSettings()

        defaults.setAutoApplyModelRules(true)
        assertTrue(first.getChatApplyModelRules())
        assertFalse(second.getChatApplyModelRules())
        assertTrue(firstStore.contains("apply_model_rules"))
        assertTrue(secondStore.contains("apply_model_rules"))
    }

    @Test
    fun archiveUsesTheMostRecentQuickSettingsChoiceForTheNextChat() {
        val global = FakeSharedPreferences()

        val (first, firstStore) = chat(global)
        first.initializeNewChatQuickSettings()
        assertFalse(first.isChatExcludedFromMemory())
        assertTrue(firstStore.contains("memory_excluded"))

        first.setChatArchiveEnabled(false)
        val (second, secondStore) = chat(global)
        second.initializeNewChatQuickSettings()
        assertTrue(second.isChatExcludedFromMemory())
        assertTrue(secondStore.contains("memory_excluded"))

        second.setChatArchiveEnabled(true)
        val (third, thirdStore) = chat(global)
        third.initializeNewChatQuickSettings()
        assertFalse(third.isChatExcludedFromMemory())
        assertTrue(thirdStore.contains("memory_excluded"))

        assertTrue(first.isChatExcludedFromMemory())
        assertFalse(second.isChatExcludedFromMemory())
    }

    @Test
    fun reusedChatSettingsAreOverwrittenOnlyForTheThreeAuthoritativeValues() {
        val global = FakeSharedPreferences().apply {
            edit()
                .putString("memory_engine", "associative")
                .putBoolean("default_memory_enabled", true)
                .putBoolean("auto_apply_model_rules", false)
                .putBoolean("last_chat_archive_enabled", false)
                .commit()
        }
        val stale = FakeSharedPreferences().apply {
            edit()
                .putString("memory_enabled", "false")
                .putBoolean("apply_model_rules", true)
                .putBoolean("memory_excluded", false)
                .putString("unrelated", "keep")
                .commit()
        }
        val preferences = Preferences(stale, global, "reused")

        preferences.initializeNewChatQuickSettings()

        assertTrue(preferences.getChatMemoryEnabled())
        assertFalse(preferences.getChatApplyModelRules())
        assertTrue(preferences.isChatExcludedFromMemory())
        assertEquals("keep", stale.getString("unrelated", ""))
    }
}
