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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ActivationPromptObject
import org.teslasoft.assistant.util.Hash

/**
 * Activation prompts must key themselves by a STABLE id, not by a hash of their
 * (mutable) label. Renaming a prompt must keep the same id so companion / per-
 * chat / global references stay valid.
 */
class ActivationPromptStableIdTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: ActivationPromptPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        store = ActivationPromptPreferences.createForTest(prefs)
    }

    @Test fun creatingAssignsAStableId() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)
        assertTrue("a new prompt gets an id", obj.id.isNotEmpty())
        assertTrue("id is a minted stable id, not a name hash", obj.id.startsWith("ap-"))
    }

    @Test fun loadingPreservesTheId() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)
        val loaded = store.getActivationPrompt(obj.id)
        assertEquals(obj.id, loaded.id)
        assertEquals("Roleplay", loaded.label)

        val fromList = store.getActivationPromptsList().single()
        assertEquals(obj.id, fromList.id)
    }

    @Test fun renamingDoesNotChangeTheId() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)
        val originalId = obj.id

        val loaded = store.getActivationPrompt(originalId)
        loaded.label = "Story Mode"
        store.setActivationPrompt(loaded)

        assertEquals("rename keeps the id", originalId, loaded.id)
        assertEquals("Story Mode", store.getActivationPrompt(originalId).label)
        // A reference (companion/per-chat) holding originalId still resolves.
        assertEquals("Story Mode", store.getActivationPrompt(originalId).label)
    }

    @Test fun editingOtherFieldsDoesNotChangeTheId() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)
        val originalId = obj.id

        val loaded = store.getActivationPrompt(originalId)
        loaded.prompt = "Different text"
        store.setActivationPrompt(loaded)

        assertEquals(originalId, loaded.id)
        assertEquals("Different text", store.getActivationPrompt(originalId).prompt)
    }

    @Test fun renamingCreatesNoDuplicateRecord() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)

        val loaded = store.getActivationPrompt(obj.id)
        loaded.label = "Story Mode"
        store.setActivationPrompt(loaded)

        assertEquals("still exactly one prompt after rename", 1, store.getActivationPromptsList().size)
    }

    @Test fun deletingUsesTheStoredId() {
        val obj = ActivationPromptObject("Roleplay", "Act as...")
        store.setActivationPrompt(obj)
        store.deleteActivationPrompt(obj.id)
        assertTrue(store.getActivationPromptsList().isEmpty())
    }

    @Test fun legacyPromptsKeepTheirHashedIdAfterLoadAndSave() {
        // Simulate a record written by the old build: keys prefixed with the
        // SHA-256 hash of the label.
        val legacyId = Hash.hash("Legacy")
        prefs.edit().putString(legacyId + "_label", "Legacy").putString(legacyId + "_prompt", "old").apply()

        val loaded = store.getActivationPromptsList().single()
        assertEquals("legacy record keeps its original hashed id", legacyId, loaded.id)

        // Renaming it must not mint a new id or leave the old one behind.
        loaded.label = "Renamed"
        store.setActivationPrompt(loaded)
        assertEquals(legacyId, loaded.id)
        assertEquals(1, store.getActivationPromptsList().size)
        assertEquals("Renamed", store.getActivationPrompt(legacyId).label)
        assertNotEquals("id is NOT re-derived from the new label", Hash.hash("Renamed"), loaded.id)
    }
}
