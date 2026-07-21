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
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * Profile Images immediate-save (July 21 2026): picking a picture for an
 * EXISTING companion must persist ONLY the avatar, by the companion's stable
 * id, without committing any unrelated unsaved editor edits and without
 * touching the stable id. A brand-new (unsaved) companion must NOT be
 * materialised into a record by an image pick alone.
 */
class PersonaAvatarImmediateSaveTest {

    private class RecordingSync : PersonaPreferences.CompanionSync {
        override fun onPersonaSaved(persona: PersonaObject) {}
        override fun onPersonaDeleted(personaId: String) {}
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: PersonaPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        store = PersonaPreferences.createForTest(prefs, RecordingSync())
    }

    @Test fun avatarOnlyUpdateKeepsStableIdAndUnrelatedFields() {
        val p = PersonaObject(
            label = "Aria",
            prompt = "You are Aria",
            activationPromptId = "ap-1",
            coreLoreBookId = "lb-core",
            additionalLoreBookIds = "lb-a,lb-b",
            autoLoadLastLoreBooks = true,
            avatarRef = "oldhash"
        )
        store.setPersona(p)
        val id = p.id

        store.setPersonaAvatarRef(id, "newhash")

        val after = store.getPersona(id)
        assertEquals("stable id unchanged", id, after.id)
        assertEquals("only the avatar changed", "newhash", after.avatarRef)
        assertEquals("Aria", after.label)
        assertEquals("You are Aria", after.prompt)
        assertEquals("ap-1", after.activationPromptId)
        assertEquals("lb-core", after.coreLoreBookId)
        assertEquals("lb-a,lb-b", after.additionalLoreBookIds)
        assertEquals(true, after.autoLoadLastLoreBooks)
        assertEquals("still exactly one companion", 1, store.getPersonasList().size)
    }

    @Test fun imageOnlyPersistDoesNotCommitUnrelatedDraftEdits() {
        // The stored companion.
        val stored = PersonaObject(label = "Aria", prompt = "stored prompt", avatarRef = "oldhash")
        store.setPersona(stored)
        val id = stored.id

        // The editor holds an unsaved DRAFT with different fields the user is
        // still editing. The immediate image save takes only (id, hash), so it
        // is structurally impossible for it to write the draft.
        @Suppress("UNUSED_VARIABLE")
        val editorDraft = PersonaObject(label = "Renamed", prompt = "draft prompt", avatarRef = "newhash", id = id)

        store.setPersonaAvatarRef(id, "newhash")

        val after = store.getPersona(id)
        assertEquals("draft label was NOT committed", "Aria", after.label)
        assertEquals("draft prompt was NOT committed", "stored prompt", after.prompt)
        assertEquals("only the picture persisted", "newhash", after.avatarRef)
    }

    @Test fun blankIdCreatesNoRecord() {
        store.setPersonaAvatarRef("", "somehash")
        assertTrue("an image pick on a brand-new companion creates no record", store.getPersonasList().isEmpty())
    }

    @Test fun listRefreshListenerFiresOnAvatarUpdate() {
        val stored = PersonaObject(label = "Aria", avatarRef = "oldhash")
        store.setPersona(stored)
        var notified = false
        store.addOnPersonaChangeListener { notified = true }
        store.setPersonaAvatarRef(stored.id, "newhash")
        assertTrue("an open companion list is told to re-render", notified)
    }
}
