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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.util.Hash

/**
 * The memory mirror (companion sync) always receives [PersonaObject.prompt],
 * which is the DEFAULT variant's text. These tests verify that only changing
 * the default affects what the mirror sees, regardless of how many variants
 * exist, which one is edited, or whether prompts are renamed.
 */
class PromptVariantMirrorTest {

    private class MirrorSync : PersonaPreferences.CompanionSync {
        val mirroredPrompts = ArrayList<String>()
        val mirroredIds = ArrayList<String>()
        val deletedIds = ArrayList<String>()
        override fun onPersonaSaved(persona: PersonaObject) {
            mirroredIds.add(persona.id)
            mirroredPrompts.add(persona.prompt)
        }
        override fun onPersonaDeleted(personaId: String) { deletedIds.add(personaId) }
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var mirror: MirrorSync
    private lateinit var store: PersonaPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        mirror = MirrorSync()
        store = PersonaPreferences.createForTest(prefs, mirror)
    }

    private fun twoPromptPersona(): PersonaObject {
        val a = CompanionPromptVariant(name = "Prompt A", text = "You are A", isDefault = true)
        val b = CompanionPromptVariant(name = "Prompt B", text = "You are B", isDefault = false)
        return PersonaObject(
            label = "Twin",
            prompt = "You are A",
            promptVariants = arrayListOf(a, b)
        )
    }

    @Test fun initialSaveMirrorsDefaultPrompt() {
        val p = twoPromptPersona()
        store.setPersona(p)

        assertEquals("mirror receives the default variant", "You are A", mirror.mirroredPrompts.single())
    }

    @Test fun editingNonDefaultDoesNotChangeMirror() {
        val p = twoPromptPersona()
        store.setPersona(p)

        val loaded = store.getPersona(p.id)
        loaded.promptVariants.first { !it.isDefault }.text = "You are B, revised"
        store.setPersona(loaded)

        assertEquals(2, mirror.mirroredPrompts.size)
        assertEquals("mirror still sees the default", "You are A", mirror.mirroredPrompts[1])
    }

    @Test fun switchingDefaultUpdatesMirror() {
        val p = twoPromptPersona()
        store.setPersona(p)

        val loaded = store.getPersona(p.id)
        loaded.promptVariants.first { it.isDefault }.isDefault = false
        val newDefault = loaded.promptVariants.first { it.name == "Prompt B" }
        newDefault.isDefault = true
        loaded.prompt = newDefault.text
        store.setPersona(loaded)

        assertEquals(2, mirror.mirroredPrompts.size)
        assertEquals("mirror now receives B", "You are B", mirror.mirroredPrompts[1])
        assertEquals("persona.prompt updated", "You are B", store.getPersona(p.id).prompt)
    }

    @Test fun renamingPromptDoesNotChangeMirror() {
        val p = twoPromptPersona()
        store.setPersona(p)

        val loaded = store.getPersona(p.id)
        loaded.promptVariants.first { it.isDefault }.name = "My Main Prompt"
        loaded.promptVariants.first { !it.isDefault }.name = "Backup Prompt"
        store.setPersona(loaded)

        assertEquals(2, mirror.mirroredPrompts.size)
        assertEquals("rename does not change mirrored content", "You are A", mirror.mirroredPrompts[1])
    }

    @Test fun legacyMigrationMirrorsTheOnlyPromptAsDefault() {
        val legacyId = Hash.hash("OldBot")
        prefs.edit()
            .putString(legacyId + "_label", "OldBot")
            .putString(legacyId + "_prompt", "Legacy personality")
            .apply()

        val loaded = store.getPersona(legacyId)
        assertEquals("migrated prompt is default", "Legacy personality", loaded.prompt)
        assertTrue("single variant is marked default", loaded.promptVariants.single().isDefault)

        store.setPersona(loaded)
        assertEquals("mirror receives the migrated default", "Legacy personality", mirror.mirroredPrompts.single())
    }
}
