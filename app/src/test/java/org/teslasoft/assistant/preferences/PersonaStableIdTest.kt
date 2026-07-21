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
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.util.Hash

/**
 * Companions ("personas") must key themselves by a STABLE id, not by a hash of
 * their (mutable) label. The rename bug was the highest-risk one: a rename used
 * to delete the old keys and rewrite under a new name-derived id, orphaning the
 * avatar, the activation-prompt link, the per-chat selection, the global
 * last-used id and the memory-store companion record.
 */
class PersonaStableIdTest {

    /** Records what the app->store companion bridge is asked to do. */
    private class RecordingSync : PersonaPreferences.CompanionSync {
        val savedIds = ArrayList<String>()
        val savedLabels = ArrayList<String>()
        val deletedIds = ArrayList<String>()
        override fun onPersonaSaved(persona: PersonaObject) {
            savedIds.add(persona.id)
            savedLabels.add(persona.label)
        }
        override fun onPersonaDeleted(personaId: String) { deletedIds.add(personaId) }
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var sync: RecordingSync
    private lateinit var store: PersonaPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        sync = RecordingSync()
        store = PersonaPreferences.createForTest(prefs, sync)
    }

    private fun sample(label: String) = PersonaObject(
        label = label,
        prompt = "You are $label",
        activationPromptId = "ap-123",
        avatarRef = "avatarhash"
    )

    @Test fun creatingAssignsAStableId() {
        val p = sample("Aria")
        store.setPersona(p)
        assertTrue(p.id.isNotEmpty())
        assertTrue("id is minted, not a name hash", p.id.startsWith("p-"))
        assertEquals("sync saw the new id", p.id, sync.savedIds.single())
    }

    @Test fun loadingPreservesTheId() {
        val p = sample("Aria")
        store.setPersona(p)
        assertEquals(p.id, store.getPersona(p.id).id)
        assertEquals(p.id, store.getPersonasList().single().id)
    }

    @Test fun renamingKeepsIdAvatarAndActivationLink() {
        val p = sample("Aria")
        store.setPersona(p)
        val originalId = p.id

        val loaded = store.getPersona(originalId)
        loaded.label = "Aria the Bold"
        store.setPersona(loaded)

        assertEquals("rename keeps the id", originalId, loaded.id)
        val after = store.getPersona(originalId)
        assertEquals("Aria the Bold", after.label)
        assertEquals("avatar stays attached", "avatarhash", after.avatarRef)
        assertEquals("activation prompt link stays attached", "ap-123", after.activationPromptId)
    }

    @Test fun editingOtherFieldsKeepsTheId() {
        val p = sample("Aria")
        store.setPersona(p)
        val id = p.id
        val loaded = store.getPersona(id)
        loaded.prompt = "changed"
        store.setPersona(loaded)
        assertEquals(id, loaded.id)
        assertEquals("changed", store.getPersona(id).prompt)
    }

    @Test fun renamingCreatesNoSecondRecordOrSecondCompanion() {
        val p = sample("Aria")
        store.setPersona(p)
        val originalId = p.id

        val loaded = store.getPersona(originalId)
        loaded.label = "Aria the Bold"
        store.setPersona(loaded)

        assertEquals("still exactly one persona", 1, store.getPersonasList().size)
        // The bridge was called with the SAME id both times, so the memory store
        // locates and updates the existing companion instead of inserting a
        // second one.
        assertEquals(2, sync.savedIds.size)
        assertEquals(originalId, sync.savedIds[0])
        assertEquals(originalId, sync.savedIds[1])
    }

    @Test fun deletingUsesTheStoredIdAndForwardsItToTheBridge() {
        val p = sample("Aria")
        store.setPersona(p)
        store.deletePersona(p.id)
        assertTrue(store.getPersonasList().isEmpty())
        assertEquals("companion delete keyed by the stable id", p.id, sync.deletedIds.single())
    }

    @Test fun legacyPersonaKeepsItsHashedIdAfterLoadAndSave() {
        val legacyId = Hash.hash("Legacy")
        prefs.edit()
            .putString(legacyId + "_label", "Legacy")
            .putString(legacyId + "_prompt", "old")
            .putString(legacyId + "_avatar_ref", "legacyAvatar")
            .apply()

        val loaded = store.getPersonasList().single()
        assertEquals("legacy persona keeps its original hashed id", legacyId, loaded.id)

        loaded.label = "Renamed"
        store.setPersona(loaded)
        assertEquals(legacyId, loaded.id)
        assertEquals(1, store.getPersonasList().size)
        assertEquals("legacyAvatar", store.getPersona(legacyId).avatarRef)
        assertNotEquals("id not re-derived from the new label", Hash.hash("Renamed"), loaded.id)
    }
}
